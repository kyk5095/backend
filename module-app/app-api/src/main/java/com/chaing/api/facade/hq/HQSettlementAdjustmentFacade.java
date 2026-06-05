package com.chaing.api.facade.hq;

import com.chaing.api.config.RedisCacheHelper;
import com.chaing.api.dto.hq.settlement.request.HQSettlementAdjustmentListRequest;
import com.chaing.api.dto.hq.settlement.request.HQSettlementAdjustmentVoucherRequest;
import com.chaing.api.dto.hq.settlement.response.HQAdjustmentFranchiseResponse;
import com.chaing.api.dto.hq.settlement.response.HQAdjustmentResponse;
import com.chaing.domain.settlements.enums.VoucherType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HQSettlementAdjustmentFacade {

        private final com.chaing.domain.settlements.service.SettlementAdjustmentService adjustmentService;
        private final com.chaing.domain.businessunits.repository.FranchiseRepository franchiseRepository;
        private final com.chaing.domain.users.repository.UserRepository userRepository;
        private final com.chaing.domain.settlements.repository.interfaces.MonthlySettlementRepository monthlySettlementRepository;
        private final com.chaing.domain.settlements.repository.interfaces.SettlementVoucherRepository settlementVoucherRepository;
        private final RedisCacheHelper redisCacheHelper;

        // 1. 드롭다운용 가맹점 목록 조회
        public List<HQAdjustmentFranchiseResponse> getFranchisesForDropdown() {
                return franchiseRepository.findAll().stream()
                                .map(f -> HQAdjustmentFranchiseResponse.of(f.getFranchiseId(), f.getName()))
                                .collect(Collectors.toList());
        }

        // 2. 드롭다운용 전표 유형 리스트 조회
        public List<VoucherType> getAdjustmentTypes() {
                return List.of(VoucherType.values()); // 실제 enum 값들 반환
        }

        // 3. 조정 전표 등록 (생성)
        @Transactional
        public void createAdjustment(HQSettlementAdjustmentVoucherRequest request,
                        com.chaing.api.security.principal.UserPrincipal principal) {
                String createdByName = userRepository.findById(principal.getId())
                                .map(com.chaing.domain.users.entity.User::getUsername)
                                .orElse("Unknown");

                com.chaing.domain.settlements.enums.AdjustmentDirection direction = request.direction();
                com.chaing.domain.returns.enums.ReturnType returnType = request.returnType();

                // ⭐️ 반품 유형(RETURN)일 경우 자동 매핑 로직 적용
                if (request.type() == com.chaing.domain.settlements.enums.VoucherType.RETURN && returnType != null) {
                        if (returnType == com.chaing.domain.returns.enums.ReturnType.MISORDER) {
                                direction = com.chaing.domain.settlements.enums.AdjustmentDirection.DECREASE; // 오배송 ->
                                                                                                              // 가맹점 차감
                        } else if (returnType == com.chaing.domain.returns.enums.ReturnType.PRODUCT_DEFECT) {
                                direction = com.chaing.domain.settlements.enums.AdjustmentDirection.INCREASE; // 상품하자 ->
                                                                                                              // 본사 보전
                        }
                }

                BigDecimal amount = BigDecimal.valueOf(request.amount());
                BigDecimal finalAmount = (direction == com.chaing.domain.settlements.enums.AdjustmentDirection.INCREASE)
                                ? amount
                                : amount.negate();

                //  추가: 기존 정산 전표(SettlementVoucher) 자동 조회 및 매핑
                java.time.YearMonth month = request.settlementMonth();
                Long settlementVoucherId = monthlySettlementRepository
                                .findByFranchiseIdAndSettlementMonth(request.franchiseId(), month)
                                .map(m -> settlementVoucherRepository
                                                .findAllByMonthlySettlementId(m.getMonthlySettlementId()))
                                .filter(list -> !list.isEmpty())
                                .map(list -> list.get(0).getSettlementVoucherId())
                                .orElse(null); // 수정: 전표가 없더라도 null로 설정하여 등록 허용

                com.chaing.domain.settlements.entity.SettlementAdjustment adjustment = com.chaing.domain.settlements.entity.SettlementAdjustment
                                .builder()
                                .settlementVoucherId(settlementVoucherId) // ⭐️ 자동 매핑된 ID 사용
                                .adjustmentCode("AD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                                .franchiseId(request.franchiseId())
                                .voucherType(request.type())
                                .occurredAt(request.occurredAt().atStartOfDay())
                                .settlementMonth(request.settlementMonth().toString())
                                .isMinus(direction == com.chaing.domain.settlements.enums.AdjustmentDirection.DECREASE)
                                .returnType(returnType) // ⭐️ 반품 사유 저장
                                .createdByName(createdByName)
                                .adjustmentAmount(finalAmount)
                                .reason(request.reason())
                                .createdBy(principal.getId())
                                .build();

                adjustmentService.create(adjustment);
                redisCacheHelper.evictByPattern("settlement:hq:*");
        }

        // 4. 조정 전표 목록 조회 (페이징)
        public Page<HQAdjustmentResponse> getAdjustments(HQSettlementAdjustmentListRequest request) {
                int page = request.page() != null ? request.page() : 0;
                int size = request.size() != null ? request.size() : 20;
                Pageable pageable = PageRequest.of(page, size);

                String settlementMonth = request.month() != null ? request.month().toString() : null;

                Page<com.chaing.domain.settlements.entity.SettlementAdjustment> adjustments = adjustmentService.getAll(
                                request.franchiseId(), request.type(), settlementMonth, pageable);

                List<Long> franchiseIds = adjustments.stream()
                                .map(com.chaing.domain.settlements.entity.SettlementAdjustment::getFranchiseId)
                                .distinct()
                                .collect(Collectors.toList());

                Map<Long, String> franchiseNameMap = franchiseRepository.findNamesByIds(franchiseIds).stream()
                                .collect(Collectors.toMap(
                                                row -> (Long) row[0],
                                                row -> (String) row[1]));

                List<HQAdjustmentResponse> dtos = adjustments.stream()
                                .map(a -> HQAdjustmentResponse.of(
                                                a.getSettlementAdjustmentId(),
                                                franchiseNameMap.getOrDefault(a.getFranchiseId(), "Unknown"),
                                                a.getVoucherType(),
                                                a.getOccurredAt().toLocalDate(),
                                                a.getAdjustmentAmount().longValue(),
                                                a.getIsMinus() ? com.chaing.domain.settlements.enums.AdjustmentDirection.DECREASE
                                                                : com.chaing.domain.settlements.enums.AdjustmentDirection.INCREASE,
                                                a.getReason(),
                                                a.getSettlementMonth(),
                                                a.getReturnType())) //  응답에 반품 사유 포함
                                .collect(Collectors.toList());

                return new PageImpl<>(dtos, pageable, adjustments.getTotalElements());
        }
}
