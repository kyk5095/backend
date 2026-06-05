package com.chaing.api.facade.hq;

import com.chaing.api.dto.hq.settlement.request.HQSettlementConfirmMonthlyRequest;
import com.chaing.api.dto.hq.settlement.response.HQConfirmFranchiseResponse;
import com.chaing.api.dto.hq.settlement.response.HQConfirmStatusCountResponse;
import com.chaing.domain.businessunits.entity.Franchise;
import com.chaing.domain.businessunits.repository.FranchiseRepository;
import com.chaing.domain.settlements.entity.DailySettlementReceipt;
import com.chaing.domain.settlements.entity.MonthlySettlement;
import com.chaing.domain.settlements.enums.SettlementStatus;
import com.chaing.domain.settlements.repository.interfaces.MonthlySettlementRepository;
import com.chaing.domain.settlements.repository.interfaces.SettlementAdjustmentRepository;
import com.chaing.domain.settlements.service.DailySettlementService;
import com.chaing.domain.settlements.service.MonthlySettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.chaing.api.config.RedisCacheHelper;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HQSettlementConfirmFacade {

    private final MonthlySettlementService monthlyService;
    private final DailySettlementService dailyService;
    private final FranchiseRepository franchiseRepository;
    private final SettlementAdjustmentRepository adjustmentRepository;
    private final MonthlySettlementRepository monthlyRepo;
    private final RedisCacheHelper redisCacheHelper;

    // 1. 상단 상태별 카운트 조회
    public HQConfirmStatusCountResponse getMonthlyStatusCounts(YearMonth month) {
        List<Franchise> allFranchises = franchiseRepository.findAll();
        List<MonthlySettlement> existingSettlements = monthlyService.getAllByMonth(month, null);
        Map<Long, SettlementStatus> statusMap = existingSettlements.stream()
                .collect(Collectors.toMap(MonthlySettlement::getFranchiseId, MonthlySettlement::getStatus));

        long draftCount = 0;
        long requestedCount = 0;
        long confirmedCount = 0;

        for (Franchise f : allFranchises) {
            SettlementStatus status = statusMap.getOrDefault(f.getFranchiseId(), SettlementStatus.CALCULATED);
            if (status == SettlementStatus.CALCULATED) draftCount++;
            else if (status == SettlementStatus.CONFIRM_REQUESTED) requestedCount++;
            else if (status == SettlementStatus.CONFIRMED) confirmedCount++;
        }

        return HQConfirmStatusCountResponse.of(draftCount, requestedCount, confirmedCount);
    }

    // 2. 월별 정산 확정 목록 페이징 조회
    public Page<HQConfirmFranchiseResponse> getMonthlyConfirmList(HQSettlementConfirmMonthlyRequest request) {
        // 모든 가맹점 조회
        List<Franchise> franchises = franchiseRepository.findAll();
        if (request.keyword() != null && !request.keyword().trim().isEmpty()) {
            franchises = franchises.stream()
                    .filter(f -> f.getName().contains(request.keyword()))
                    .collect(Collectors.toList());
        }

        List<MonthlySettlement> existingSettlements = monthlyService.getAllByMonth(request.month(), null);
        Map<Long, MonthlySettlement> settlementMap = existingSettlements.stream()
                .collect(Collectors.toMap(MonthlySettlement::getFranchiseId, s -> s));

        List<HQConfirmFranchiseResponse> dtos = new ArrayList<>();
        for (Franchise f : franchises) {
            MonthlySettlement ms = settlementMap.get(f.getFranchiseId());
            SettlementStatus status = (ms != null) ? ms.getStatus() : SettlementStatus.CALCULATED;
            
            // 상태 필터링
            if (request.status() != null && status != request.status()) {
                continue;
            }

            long finalAmount;
            if (status == SettlementStatus.CONFIRMED && ms != null) {
                // 최종 확정된 경우엔 저장된 스냅샷 금액 사용
                finalAmount = ms.getFinalSettlementAmount().longValue();
            } else {
                // 그 외엔(작성중, 확정요청) 실시간 집계 금액 표시
                finalAmount = calculatePotentialAmount(f.getFranchiseId(), request.month());
            }

            dtos.add(HQConfirmFranchiseResponse.of(
                    f.getFranchiseId(),
                    f.getName(),
                    finalAmount,
                    status));
        }

        // 페이지네이션
        int page = request.page() != null ? request.page() : 0;
        int size = request.size() != null ? request.size() : 20;
        Pageable pageable = PageRequest.of(page, size);

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), dtos.size());

        if (start > dtos.size()) {
            return new PageImpl<>(List.of(), pageable, dtos.size());
        }

        return new PageImpl<>(dtos.subList(start, end), pageable, dtos.size());
    }

    private long calculatePotentialAmount(Long franchiseId, YearMonth month) {
        List<DailySettlementReceipt> receipts = dailyService.getAllByFranchiseAndDateRange(
                franchiseId, month.atDay(1), month.atEndOfMonth());
        
        BigDecimal totalFinal = receipts.stream()
                .map(DailySettlementReceipt::getFinalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal adj = adjustmentRepository.sumAdjustmentAmountByMonth(month.toString(), franchiseId);
        return totalFinal.add(adj != null ? adj : BigDecimal.ZERO).longValue();
    }

    @Transactional
    public void requestConfirm(Long franchiseId, YearMonth month) {
        MonthlySettlement ms = getOrCreateMonthlySettlement(franchiseId, month);
        monthlyService.requestConfirm(ms.getMonthlySettlementId());
        redisCacheHelper.evictByPattern("settlement:hq:*");
    }

    @Transactional
    public void finalizeConfirm(Long franchiseId, YearMonth month) {
        MonthlySettlement ms = getOrCreateMonthlySettlement(franchiseId, month);
        monthlyService.confirm(ms.getMonthlySettlementId());
        redisCacheHelper.evictByPattern("settlement:hq:*");
    }

    @Transactional
    public void rollbackToDraft(Long franchiseId, YearMonth month) {
        MonthlySettlement ms = getOrCreateMonthlySettlement(franchiseId, month);
        monthlyService.rollback(ms.getMonthlySettlementId());
        redisCacheHelper.evictByPattern("settlement:hq:*");
    }

    @Transactional
    public void finalizeAll(YearMonth month) {
        List<MonthlySettlement> settlements = monthlyService.getAllByMonth(month, null);
        settlements.stream()
                .filter(s -> s.getStatus() == SettlementStatus.CONFIRM_REQUESTED)
                .forEach(s -> monthlyService.confirm(s.getMonthlySettlementId()));
        redisCacheHelper.evictByPattern("settlement:hq:*");
    }

    private MonthlySettlement getOrCreateMonthlySettlement(Long franchiseId, YearMonth month) {
        return monthlyService.findByFranchiseAndMonth(franchiseId, month)
                .orElseGet(() -> {
                    List<DailySettlementReceipt> receipts = dailyService.getAllByFranchiseAndDateRange(
                            franchiseId, month.atDay(1), month.atEndOfMonth());
                    
                    BigDecimal totalSale = BigDecimal.ZERO;
                    BigDecimal orderAmt = BigDecimal.ZERO;
                    BigDecimal delivery = BigDecimal.ZERO;
                    BigDecimal commission = BigDecimal.ZERO;
                    BigDecimal refund = BigDecimal.ZERO;
                    BigDecimal loss = BigDecimal.ZERO;
                    BigDecimal finalAmt = BigDecimal.ZERO;

                    for (DailySettlementReceipt r : receipts) {
                        totalSale = totalSale.add(r.getTotalSaleAmount());
                        orderAmt = orderAmt.add(r.getOrderAmount());
                        delivery = delivery.add(r.getDeliveryFee());
                        commission = commission.add(r.getCommissionFee());
                        refund = refund.add(r.getRefundAmount());
                        loss = loss.add(r.getLossAmount());
                        finalAmt = finalAmt.add(r.getFinalAmount());
                    }

                    BigDecimal adj = adjustmentRepository.sumAdjustmentAmountByMonth(month.toString(), franchiseId);
                    BigDecimal currentAdj = adj != null ? adj : BigDecimal.ZERO;

                    return createAndSaveMonthlySettlement(franchiseId, month, totalSale, orderAmt, delivery, commission, refund, loss, currentAdj, finalAmt.add(currentAdj));
                });
    }

    private MonthlySettlement createAndSaveMonthlySettlement(Long franchiseId, YearMonth month, 
            BigDecimal totalSale, BigDecimal orderAmt, BigDecimal delivery, BigDecimal commission, BigDecimal refund, BigDecimal loss, BigDecimal adjAmt, BigDecimal finalAmt) {
        MonthlySettlement ms = MonthlySettlement.builder()
                .franchiseId(franchiseId)
                .settlementMonth(month)
                .totalSaleAmount(totalSale)
                .orderAmount(orderAmt)
                .deliveryFee(delivery)
                .commissionFee(commission)
                .refundAmount(refund)
                .lossAmount(loss)
                .adjustmentAmount(adjAmt)
                .finalSettlementAmount(finalAmt)
                .status(SettlementStatus.CALCULATED)
                .build();
        return monthlyRepo.save(ms);
    }
}
