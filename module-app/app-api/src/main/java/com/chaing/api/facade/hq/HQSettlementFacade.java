package com.chaing.api.facade.hq;

import com.chaing.api.dto.franchise.settlement.response.FranchiseSettlementSummaryResponse;
import com.chaing.api.dto.franchise.settlement.response.FranchiseVoucherResponse;
import com.chaing.api.dto.hq.settlement.request.*;
import com.chaing.api.dto.hq.settlement.response.*;
import com.chaing.domain.settlements.enums.PeriodType;
import com.chaing.domain.settlements.enums.VoucherType;
import com.chaing.domain.settlements.enums.SettlementStatus;
import com.chaing.domain.settlements.service.DailySettlementService;
import com.chaing.domain.settlements.service.MonthlySettlementService;
import com.chaing.domain.settlements.service.SettlementFileService;
import com.chaing.domain.settlements.service.SettlementDocumentService;
import com.chaing.domain.settlements.repository.interfaces.SettlementAdjustmentRepository;
import com.chaing.domain.settlements.repository.interfaces.SettlementVoucherRepository;
import com.chaing.domain.settlements.entity.MonthlySettlement;
import com.chaing.domain.settlements.entity.SettlementDocument;
import com.chaing.domain.settlements.exception.SettlementException;
import com.chaing.domain.settlements.exception.SettlementErrorCode;
import com.chaing.domain.settlements.enums.DocumentType;
import com.chaing.domain.settlements.enums.DocumentOwner;
import com.chaing.core.enums.BucketName;
import com.chaing.core.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.chaing.domain.businessunits.repository.FranchiseRepository;
import com.chaing.domain.businessunits.entity.Franchise;
import com.chaing.domain.orders.entity.FranchiseOrder;
import com.chaing.domain.orders.entity.FranchiseOrderItem;
import com.chaing.domain.orders.enums.FranchiseOrderStatus;
import com.chaing.domain.orders.repository.FranchiseOrderItemRepository;
import com.chaing.domain.orders.repository.FranchiseOrderRepository;
import com.chaing.domain.sales.entity.SalesItem;
import com.chaing.domain.sales.repository.FranchiseSalesItemRepository;
import com.chaing.domain.settlements.entity.DailyReceiptLine;
import com.chaing.domain.settlements.entity.DailySettlementReceipt;
import com.chaing.domain.returns.entity.Returns;
import com.chaing.domain.returns.enums.ReturnStatus;
import com.chaing.domain.returns.enums.ReturnType;
import com.chaing.domain.returns.entity.ReturnItem;
import com.chaing.domain.returns.repository.FranchiseReturnItemRepository;
import com.chaing.domain.returns.repository.FranchiseReturnRepository;
import com.chaing.domain.settlements.entity.SettlementVoucher;
import com.chaing.api.config.RedisCacheHelper;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.chaing.api.dto.franchise.settlement.response.FranchiseSalesItemResponse;
import com.chaing.api.dto.franchise.settlement.response.FranchiseOrderItemResponse;
import com.chaing.api.dto.franchise.settlement.response.FranchiseDailyGraphResponse;
import com.chaing.domain.products.entity.Product;
import com.chaing.domain.products.repository.ProductRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class HQSettlementFacade {

    private final DailySettlementService dailyService;
    private final MonthlySettlementService monthlyService;
    private final SettlementFileService fileService;
    private final MinioService minioService;
    private final SettlementDocumentService documentService;
    private final SettlementVoucherRepository voucherRepository;
    private final SettlementAdjustmentRepository adjustmentRepository;
    private final FranchiseRepository franchiseRepository;

    private final FranchiseSalesItemRepository salesItemRepository;
    private final FranchiseOrderRepository orderRepository;
    private final FranchiseOrderItemRepository orderItemRepository;
    private final FranchiseReturnRepository returnRepository;
    private final FranchiseReturnItemRepository returnItemRepository;
    private final ProductRepository productRepository;
    private final RedisCacheHelper redisCacheHelper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private static final Duration CACHE_TTL = Duration.ofMinutes(10); // 정산 요약은 자주 변하지 않으므로 10분 TTL 설정

    // 1. 일별 조회

    @Transactional(readOnly = true)
    public HQSettlementSummaryResponse getDailySummary(HQSettlementDailySummaryRequest request) {
        //캐시
        String cacheKey = "settlement:hq:summary:daily:" + request.date().toString();
        HQSettlementSummaryResponse cached = readObjectCache(cacheKey, HQSettlementSummaryResponse.class);
        if (cached != null) {
            log.info("[REDIS HIT] getDailySummary hit for key: {}", cacheKey);
            return cached;
        }

        // 모든 가맹점 조회
        List<Franchise> franchises = franchiseRepository.findAll();

        BigDecimal hqTotalFinal = BigDecimal.ZERO;
        BigDecimal totalOrder = BigDecimal.ZERO;
        BigDecimal totalSale = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalDelivery = BigDecimal.ZERO;
        BigDecimal totalRefund = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;

        for (Franchise franchise : franchises) {
            AggregationResult result = getInternalDailyAggregation(franchise.getFranchiseId(),
                    request.date());
            DailySettlementReceipt r = result.receipt();

            totalOrder = totalOrder.add(r.getOrderAmount());
            totalSale = totalSale.add(r.getTotalSaleAmount());
            totalCommission = totalCommission.add(r.getCommissionFee());
            totalDelivery = totalDelivery.add(r.getDeliveryFee());
            totalRefund = totalRefund.add(r.getRefundAmount());
            totalLoss = totalLoss.add(r.getLossAmount());
        }

        hqTotalFinal = totalOrder.add(totalCommission)
                .subtract(totalDelivery).subtract(totalRefund).subtract(totalLoss);

        // 조정 금액 합산 (해당 월의 전체 조정)
        String monthStr = request.date().toString().substring(0, 7);
        BigDecimal totalAdjustment = adjustmentRepository.sumAdjustmentAmountByMonth(monthStr, null);
        hqTotalFinal = hqTotalFinal.add(totalAdjustment);

        HQSettlementSummaryResponse response = HQSettlementSummaryResponse.of(hqTotalFinal, totalOrder, totalSale, totalCommission,
                totalDelivery, totalRefund, totalLoss, totalAdjustment);

        writeCache(cacheKey, response);
        return response;

    }

    @Transactional(readOnly = true)
    public Page<HQFranchiseSettlementResponse> getDailyFranchises(HQSettlementDailyFranchisesRequest request) {
        // 1. 가맹점 목록 조회 (검색어 포함)
        List<Franchise> franchises;
        if (request.keyword() != null && !request.keyword().trim().isEmpty()) {
            // 간단한 인메모리 필터링 (가맹점 수가 적으므로)
            franchises = franchiseRepository.findAll().stream()
                    .filter(f -> f.getName().contains(request.keyword()))
                    .collect(Collectors.toList());
        } else {
            franchises = franchiseRepository.findAll();
        }

        // 2. 가맹점별 실시간 집계
        List<HQFranchiseSettlementResponse> dtos = new ArrayList<>();
        for (Franchise f : franchises) {
            AggregationResult result = getInternalDailyAggregation(f.getFranchiseId(), request.date());
            DailySettlementReceipt r = result.receipt();

            // 개별 가맹점 조정 금액 (해당 월)
            String monthStr = request.date().toString().substring(0, 7);
            BigDecimal adjAmount = adjustmentRepository.sumAdjustmentAmountByMonth(monthStr, f.getFranchiseId());
            BigDecimal finalAmount = r.getFinalAmount().add(adjAmount);

            dtos.add(HQFranchiseSettlementResponse.of(
                    f.getFranchiseId(),
                    f.getName(),
                    r.getTotalSaleAmount(),
                    r.getOrderAmount(),
                    r.getDeliveryFee(),
                    r.getCommissionFee(),
                    r.getRefundAmount(),
                    r.getLossAmount(),
                    adjAmount,
                    finalAmount, // 가맹점 관점 최종 정산액 (조정 포함)
                    SettlementStatus.CONFIRMED, // 가집계이므로 논리적으로 CONFIRMED 또는 PENDING 처리 가능
                    r.getSettlementDate()));
        }

        // 3. 페이지네이션 처리
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

    @Transactional(readOnly = true)
    public List<HQDailyGraphResponse> getDailyTrend(HQSettlementDailyGraphRequest request) {
        List<com.chaing.domain.settlements.entity.DailySettlementReceipt> receipts = dailyService
                .getAllByDateRange(request.start(), request.end());

        // 날짜별로 그룹화하여 totalSaleAmount 합산
        Map<LocalDate, BigDecimal> dailySums = receipts.stream()
                .collect(Collectors.groupingBy(
                        com.chaing.domain.settlements.entity.DailySettlementReceipt::getSettlementDate,
                        Collectors.reducing(BigDecimal.ZERO,
                                com.chaing.domain.settlements.entity.DailySettlementReceipt::getTotalSaleAmount,
                                BigDecimal::add)));

        return dailySums.entrySet().stream()
                .map(entry -> HQDailyGraphResponse.of(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> a.date().compareTo(b.date())) // 날짜 오름차순 정렬
                .collect(Collectors.toList());
    }

    // 2. 월별 조회

    @Transactional(readOnly = true)
    public HQSettlementSummaryResponse getMonthlySummary(HQSettlementMonthlySummaryRequest request) {

        //캐시 조회
        String cacheKey = "settlement:hq:summary:monthly:" + request.month().toString();
        HQSettlementSummaryResponse cached = readObjectCache(cacheKey, HQSettlementSummaryResponse.class);
        if (cached != null) {
            log.info("[REDIS HIT] getMonthlySummary hit for key: {}", cacheKey);
            return cached;
        }

        List<com.chaing.domain.settlements.entity.MonthlySettlement> settlements = monthlyService
                .getAllByMonth(request.month(), null);

        BigDecimal totalOrder = settlements.stream()
                .map(com.chaing.domain.settlements.entity.MonthlySettlement::getOrderAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSale = settlements.stream()
                .map(com.chaing.domain.settlements.entity.MonthlySettlement::getTotalSaleAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCommission = settlements.stream()
                .map(com.chaing.domain.settlements.entity.MonthlySettlement::getCommissionFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDelivery = settlements.stream()
                .map(com.chaing.domain.settlements.entity.MonthlySettlement::getDeliveryFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRefund = settlements.stream()
                .map(com.chaing.domain.settlements.entity.MonthlySettlement::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLoss = settlements.stream()
                .map(com.chaing.domain.settlements.entity.MonthlySettlement::getLossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 본사 관점 최종 정산 금액
        BigDecimal totalAdjustment = adjustmentRepository.sumAdjustmentAmountByMonth(request.month().toString(), null);
        BigDecimal hqTotalFinal = totalOrder.add(totalCommission)
                .subtract(totalDelivery).subtract(totalRefund).subtract(totalLoss).add(totalAdjustment);

        HQSettlementSummaryResponse response = HQSettlementSummaryResponse.of(hqTotalFinal, totalOrder, totalSale, totalCommission,
                totalDelivery, totalRefund, totalLoss, totalAdjustment);

        writeCache(cacheKey, response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<HQFranchiseSettlementResponse> getMonthlyFranchises(HQSettlementMonthlyFranchisesRequest request) {
        List<com.chaing.domain.settlements.entity.MonthlySettlement> settlements = monthlyService
                .getAllByMonth(request.month(), request.keyword());

        // 상태 필터링 처리
        if (request.status() != null) {
            settlements = settlements.stream()
                    .filter(s -> s.getStatus() == request.status())
                    .collect(Collectors.toList());
        }

        // 2. 가맹점 정보 벌크 조회 (N+1 쿼리 방지)
        List<Long> franchiseIds = settlements.stream()
                .map(com.chaing.domain.settlements.entity.MonthlySettlement::getFranchiseId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, String> franchiseNameMap = franchiseRepository.findAllById(franchiseIds).stream()
                .collect(Collectors.toMap(Franchise::getFranchiseId, Franchise::getName));

        List<HQFranchiseSettlementResponse> dtos = settlements.stream()
                .map(s -> {
                    String franchiseName = franchiseNameMap.getOrDefault(s.getFranchiseId(),
                            "Unknown");
                    BigDecimal adjAmount = adjustmentRepository.sumAdjustmentAmountByMonth(s.getSettlementMonth().toString(), s.getFranchiseId());
                    BigDecimal finalSettlementAmount = s.getFinalSettlementAmount().add(adjAmount);

                    return HQFranchiseSettlementResponse.of(
                            s.getFranchiseId(),
                            franchiseName,
                            s.getTotalSaleAmount(),
                            s.getOrderAmount(),
                            s.getDeliveryFee(),
                            s.getCommissionFee(),
                            s.getRefundAmount(),
                            s.getLossAmount(),
                            adjAmount,
                            finalSettlementAmount,
                            s.getStatus(),
                            s.getSettlementMonth().atDay(1)); // 기준일
                })
                .collect(Collectors.toList());

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

    @Transactional(readOnly = true)
    public List<HQMonthlyGraphResponse> getMonthlyTrend(HQSettlementMonthlyGraphRequest request) {
        // start ~ end 기간 사이의 모든 월별 정산 데이터 조회

        YearMonth startMonth = YearMonth.from(request.start());
        YearMonth endMonth = YearMonth.from(request.end());

        List<HQMonthlyGraphResponse> result = new java.util.ArrayList<>();
        YearMonth current = startMonth;

        while (!current.isAfter(endMonth)) {
            List<com.chaing.domain.settlements.entity.MonthlySettlement> settlements = monthlyService
                    .getAllByMonth(current, null);
            BigDecimal totalSaleAmount = settlements.stream()
                    .map(com.chaing.domain.settlements.entity.MonthlySettlement::getTotalSaleAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(HQMonthlyGraphResponse.of(current, totalSaleAmount));
            current = current.plusMonths(1);
        }

        return result;
    }

    // 3. 단건 가맹점 상세 내역 (Drill-down), 전표 조회

    @Transactional(readOnly = true)
    public FranchiseSettlementSummaryResponse getDailyFranchiseSummary(Long franchiseId,
                                                                       HQSettlementFranchiseDailyDetailRequest request) {
        com.chaing.domain.settlements.entity.DailySettlementReceipt receipt = dailyService
                .getByFranchiseAndDate(franchiseId, request.date());

        String franchiseName = franchiseRepository.findById(franchiseId)
                .map(Franchise::getName)
                .orElse("Unknown Store");

        return new FranchiseSettlementSummaryResponse(
                franchiseName,
                receipt.getFinalAmount(),
                receipt.getTotalSaleAmount(),
                receipt.getRefundAmount(),
                receipt.getOrderAmount(),
                receipt.getDeliveryFee(),
                receipt.getLossAmount(),
                receipt.getCommissionFee(),
                receipt.getAdjustmentAmount());
    }

    @Transactional(readOnly = true)
    public FranchiseSettlementSummaryResponse getMonthlyFranchiseSummary(Long franchiseId,
                                                                         HQSettlementFranchiseMonthlyDetailRequest request) {
        com.chaing.domain.settlements.entity.MonthlySettlement settlement = monthlyService
                .getByFranchiseAndMonth(franchiseId, request.month());

        String franchiseName = franchiseRepository.findById(franchiseId)
                .map(Franchise::getName)
                .orElse("Unknown Store");

        return new FranchiseSettlementSummaryResponse(
                franchiseName,
                settlement.getFinalSettlementAmount(),
                settlement.getTotalSaleAmount(),
                settlement.getRefundAmount(),
                settlement.getOrderAmount(),
                settlement.getDeliveryFee(),
                settlement.getLossAmount(),
                settlement.getCommissionFee(),
                settlement.getAdjustmentAmount());
    }

    @Transactional(readOnly = true)
    public Page<FranchiseVoucherResponse> getFranchiseVouchers(Long franchiseId, PeriodType period, LocalDate date,
                                                               YearMonth month, VoucherType type, int page, int size) {

        PageRequest pageable = PageRequest.of(page, size);

        if (period == PeriodType.DAILY) {
            // 일별: DailySettlementService에서 Receipt 조회 후 ReceiptLine을 Response로 변환
            com.chaing.domain.settlements.entity.DailySettlementReceipt receipt = dailyService
                    .getByFranchiseAndDate(franchiseId, date);
            Page<com.chaing.domain.settlements.entity.DailyReceiptLine> lines = dailyService
                    .getReceiptLines(receipt.getDailyReceiptId(), type, pageable);

            return lines.map(line -> new FranchiseVoucherResponse(
                    line.getReferenceCode(),
                    line.getLineType(),
                    line.getDescription(),
                    line.getQuantity(),
                    line.getAmount(),
                    line.getOccurredAt()));

        } else {
            // 월별: MonthlySettlement 조회 후 SettlementVoucher를 Response로 변환
            com.chaing.domain.settlements.entity.MonthlySettlement settlement = monthlyService
                    .getByFranchiseAndMonth(franchiseId, month);
            // Page 처리를 위해 페이징 쿼리 사용 (또는 리스트 변환 후 페이징)
            List<com.chaing.domain.settlements.entity.SettlementVoucher> vouchers = voucherRepository
                    .findAllByMonthlySettlementId(settlement.getMonthlySettlementId());

            List<FranchiseVoucherResponse> dtos = vouchers.stream()
                    .filter(v -> type == null || v.getVoucherType() == type)
                    .map(v -> new FranchiseVoucherResponse(
                            v.getReferenceCode(),
                            v.getVoucherType(),
                            v.getDescription(),
                            v.getQuantity(),
                            v.getAmount(),
                            v.getOccurredAt()))
                    .collect(Collectors.toList());

            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), dtos.size());
            if (start > dtos.size())
                return Page.empty();

            return new PageImpl<>(dtos.subList(start, end), pageable, dtos.size());
        }
    }

    @Transactional(readOnly = true)
    public Page<FranchiseVoucherResponse> getAllVouchers(PeriodType period, LocalDate date, YearMonth month,
                                                         VoucherType type, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);

        if (period == PeriodType.DAILY) {
            List<com.chaing.domain.settlements.entity.DailySettlementReceipt> receipts = dailyService
                    .getAllByDate(date, null);
            List<FranchiseVoucherResponse> allLines = receipts.stream()
                    .flatMap(r -> dailyService.getAllReceiptLines(r.getDailyReceiptId()).stream())
                    .filter(line -> type == null || line.getLineType() == type)
                    .map(line -> new FranchiseVoucherResponse(
                            line.getReferenceCode(),
                            line.getLineType(),
                            line.getDescription(),
                            line.getQuantity(),
                            line.getAmount(),
                            line.getOccurredAt()))
                    .collect(Collectors.toList());

            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), allLines.size());
            if (start > allLines.size())
                return Page.empty();
            return new PageImpl<>(allLines.subList(start, end), pageable, allLines.size());

        } else {
            List<com.chaing.domain.settlements.entity.MonthlySettlement> settlements = monthlyService
                    .getAllByMonth(month, null);
            List<Long> settlementIds = settlements.stream()
                    .map(com.chaing.domain.settlements.entity.MonthlySettlement::getMonthlySettlementId)
                    .collect(Collectors.toList());

            if (settlementIds.isEmpty())
                return Page.empty();

            List<com.chaing.domain.settlements.entity.SettlementVoucher> vouchers = voucherRepository
                    .findAllByMonthlySettlementIdIn(settlementIds);

            List<FranchiseVoucherResponse> dtos = vouchers.stream()
                    .filter(v -> type == null || v.getVoucherType() == type)
                    .map(v -> new FranchiseVoucherResponse(
                            v.getReferenceCode(),
                            v.getVoucherType(),
                            v.getDescription(),
                            v.getQuantity(),
                            v.getAmount(),
                            v.getOccurredAt()))
                    .collect(Collectors.toList());

            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), dtos.size());
            if (start > dtos.size())
                return Page.empty();
            return new PageImpl<>(dtos.subList(start, end), pageable, dtos.size());
        }
    }

    // 4. PDF 및 엑셀 다운로드 (URL 반환)
    @Transactional
    public String getDailyAllSummaryPdf(HQSettlementDailyAllPdfRequest request) {
        log.info("[DEBUG] Facade - getDailyAllSummaryPdf requested for date: {}", request.date());
        // 1. 이미 생성된 문서가 있는지 확인
        java.util.Optional<com.chaing.domain.settlements.entity.SettlementDocument> existingDoc = documentService
                .getHQDailyDocument(request.date());
        if (existingDoc.isPresent()) {
            return minioService.getFileUrl(existingDoc.get().getObjectKey(), BucketName.SETTLEMENTS);
        }

        try {
            // 2. 해당 날짜의 모든 가맹점 정산 요약 데이터 조회
            List<com.chaing.domain.settlements.entity.DailySettlementReceipt> receipts = dailyService
                    .getAllByDate(request.date(), null);

            if (receipts.isEmpty()) {
                // DB에 데이터가 없으면 전체 가맹점 실시간 집계 시도
                List<Franchise> franchises = franchiseRepository.findAll();
                receipts = franchises.stream()
                        .map(f -> getInternalDailyAggregation(f.getFranchiseId(), request.date())
                                .receipt())
                        .collect(Collectors.toList());
            }

            if (receipts.isEmpty()) {
                log.warn("[WARN] No receipts found for HQ Daily PDF: {}", request.date());
                throw new com.chaing.domain.settlements.exception.SettlementException(
                        com.chaing.domain.settlements.exception.SettlementErrorCode.SETTLEMENT_DATA_EMPTY);
            }

            // 3. 파일 생성 서비스 호출
            byte[] pdfBytes = fileService.createHQSettlementDailyPdf(request.date(), receipts);

            // 4. MinIO 업로드
            String fileName = "settlement/daily/HQ_Daily_Settlement_" + request.date() + "_"
                    + System.currentTimeMillis() + ".pdf";
            minioService.uploadFile(pdfBytes, fileName, "application/pdf", BucketName.SETTLEMENTS);
            String fileUrl = minioService.getFileUrl(fileName, BucketName.SETTLEMENTS);
            log.info("=================> fileUrl: {}", fileUrl);
            // 5. 메타데이터 저장
            documentService.save(com.chaing.domain.settlements.entity.SettlementDocument.builder()
                    .periodType(PeriodType.DAILY)
                    .documentType(com.chaing.domain.settlements.enums.DocumentType.HQ_DAILY_SUM)
                    .documentOwner(com.chaing.domain.settlements.enums.DocumentOwner.HQ)
                    .settlementDate(request.date())
                    .storageProvider("MINIO")
                    .bucket(BucketName.SETTLEMENTS.getBucketName())
                    .objectKey(fileName)
                    .fileUrl(fileUrl)
                    .fileName(fileName.substring(fileName.lastIndexOf("/") + 1))
                    .contentType("application/pdf")
                    .fileSize((long) pdfBytes.length)
                    .build());

            return fileUrl;
        } catch (com.chaing.domain.settlements.exception.SettlementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate HQ Daily PDF: ", e);
            throw new com.chaing.domain.settlements.exception.SettlementException(
                    com.chaing.domain.settlements.exception.SettlementErrorCode.DOCUMENT_GENERATION_FAILED);
        }
    }

    @Transactional
    public String getMonthlyAllSummaryPdf(HQSettlementMonthlyAllPdfRequest request) {
        // 1. 이미 생성된 문서가 있는지 확인
        java.util.Optional<com.chaing.domain.settlements.entity.SettlementDocument> existingDoc = documentService
                .getHQMonthlyDocument(request.month());
        if (existingDoc.isPresent()) {
            return minioService.getFileUrl(existingDoc.get().getObjectKey(), BucketName.SETTLEMENTS);
        }

        try {
            // 2. 해당 월의 모든 가맹점 정산 데이터 조회
            List<com.chaing.domain.settlements.entity.MonthlySettlement> settlements = monthlyService
                    .getAllByMonth(request.month(), null);

            if (settlements.isEmpty()) {
                // DB에 데이터가 없으면 전체 가맹점 실시간 집계 시도
                List<Franchise> franchises = franchiseRepository.findAll();
                settlements = new ArrayList<>();
                for (Franchise f : franchises) {
                    LocalDate startDay = request.month().atDay(1);
                    LocalDate endDay = request.month().atEndOfMonth();
                    LocalDate todayLimit = LocalDate.now();
                    if (endDay.isAfter(todayLimit)) endDay = todayLimit;

                    List<com.chaing.domain.settlements.entity.DailySettlementReceipt> dailyReceipts = new ArrayList<>();
                    for (LocalDate d = startDay; !d.isAfter(endDay); d = d.plusDays(1)) {
                        com.chaing.domain.settlements.entity.DailySettlementReceipt dr = getInternalDailyAggregation(
                                f.getFranchiseId(), d).receipt();
                        if (dr.getTotalSaleAmount().compareTo(BigDecimal.ZERO) > 0 ||
                                dr.getOrderAmount().compareTo(BigDecimal.ZERO) > 0 ||
                                dr.getDeliveryFee().compareTo(BigDecimal.ZERO) > 0) {
                            dailyReceipts.add(dr);
                        }
                    }

                    if (!dailyReceipts.isEmpty()) {
                        settlements.add(aggregateMonthlySettlement(f.getFranchiseId(), request.month(),
                                dailyReceipts));
                    }
                }
            }

            if (settlements.isEmpty()) {
                throw new com.chaing.domain.settlements.exception.SettlementException(
                        com.chaing.domain.settlements.exception.SettlementErrorCode.SETTLEMENT_DATA_EMPTY);
            }

            // 3. 파일 생성 서비스 호출
            byte[] pdfBytes = fileService.createHQSettlementMonthlyPdf(request.month(), settlements);

            // 4. MinIO 업로드
            String fileName = "settlement/monthly/HQ_Monthly_Report_" + request.month() + "_"
                    + System.currentTimeMillis() + ".pdf";
            minioService.uploadFile(pdfBytes, fileName, "application/pdf", BucketName.SETTLEMENTS);
            String fileUrl = minioService.getFileUrl(fileName, BucketName.SETTLEMENTS);

            // 5. 메타데이터 저장
            documentService.save(com.chaing.domain.settlements.entity.SettlementDocument.builder()
                    .periodType(PeriodType.MONTHLY)
                    .documentType(com.chaing.domain.settlements.enums.DocumentType.HQ_MONTHLY_SUM)
                    .documentOwner(com.chaing.domain.settlements.enums.DocumentOwner.HQ)
                    .settlementMonth(request.month())
                    .storageProvider("MINIO")
                    .bucket(BucketName.SETTLEMENTS.getBucketName())
                    .objectKey(fileName)
                    .fileUrl(fileUrl)
                    .fileName(fileName.substring(fileName.lastIndexOf("/") + 1))
                    .contentType("application/pdf")
                    .fileSize((long) pdfBytes.length)
                    .build());

            return fileUrl;
        } catch (com.chaing.domain.settlements.exception.SettlementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate HQ Monthly PDF: ", e);
            throw new com.chaing.domain.settlements.exception.SettlementException(
                    com.chaing.domain.settlements.exception.SettlementErrorCode.DOCUMENT_GENERATION_FAILED);
        }
    }

    @Transactional
    public String getDailyFranchiseReceiptPdf(Long franchiseId,
                                              HQSettlementFranchiseDailyReceiptPdfRequest request) {
        try {
            // 1. 해당 가맹점의 일별 정산 데이터 조회 (Optional 사용으로 트랜잭션 롤백 방지)
            java.util.Optional<com.chaing.domain.settlements.entity.DailySettlementReceipt> receiptOpt = dailyService
                    .findByFranchiseAndDate(franchiseId, request.date());

            if (receiptOpt.isPresent()) {
                com.chaing.domain.settlements.entity.DailySettlementReceipt receipt = receiptOpt.get();
                // 2. 해당 정산 ID로 이미 생성된 문서가 있는지 확인
                java.util.Optional<SettlementDocument> existingDoc = documentService
                        .getDailyDocument(receipt.getDailyReceiptId());
                if (existingDoc.isPresent()) {
                    return minioService.getFileUrl(existingDoc.get().getObjectKey(),
                            BucketName.SETTLEMENTS);
                }
            }

            // 3. 문서가 없으면 실시간 생성 (상세 라인 포함)
            AggregationResult result = aggregateDailySettlement(franchiseId, request.date());
            com.chaing.domain.settlements.entity.DailySettlementReceipt currentReceipt = result.receipt();
            List<com.chaing.domain.settlements.entity.DailyReceiptLine> currentLines = result.lines();

            // [수정] 매출/발주가 0원이더라도 조정 전표 확인 등을 위해 PDF 생성을 허용함
                        /*
                        if (currentReceipt.getTotalSaleAmount().compareTo(BigDecimal.ZERO) == 0 &&
                                        currentReceipt.getOrderAmount().compareTo(BigDecimal.ZERO) == 0) {
                                throw new com.chaing.domain.settlements.exception.SettlementException(
                                                com.chaing.domain.settlements.exception.SettlementErrorCode.SETTLEMENT_DATA_EMPTY);
                        }
                        */

            // 가맹점명 조회
            String franchiseName = franchiseRepository.findById(franchiseId)
                    .map(com.chaing.domain.businessunits.entity.Franchise::getName)
                    .orElse("Unknown Store");

            byte[] pdfBytes = fileService.createDailyReceiptPdf(currentReceipt, currentLines, franchiseName);

            // 4. MinIO 업로드 (확정 데이터면 daily, 아니면 provisional 폴더)
            String folder = receiptOpt.isPresent() ? "settlement/daily/" : "settlement/provisional/";
            String prefix = receiptOpt.isPresent() ? "FR_" : "FR_Preview_";
            String fileName = folder + prefix + franchiseId + "_Daily_" + request.date() + "_"
                    + System.currentTimeMillis() + ".pdf";

            minioService.uploadFile(pdfBytes, fileName, "application/pdf", BucketName.SETTLEMENTS);
            String fileUrl = minioService.getFileUrl(fileName, BucketName.SETTLEMENTS);

            // 5. DB에 메타데이터 저장
            SettlementDocument.SettlementDocumentBuilder docBuilder = SettlementDocument
                    .builder()
                    .periodType(PeriodType.DAILY)
                    .documentType(receiptOpt.isPresent()
                            ? DocumentType.RECEIPT_PDF
                            : DocumentType.PROVISIONAL_RECEIPT_PDF)
                    .documentOwner(DocumentOwner.FRANCHISE)
                    .franchiseId(franchiseId)
                    .storageProvider("MINIO")
                    .bucket(BucketName.SETTLEMENTS.getBucketName())
                    .objectKey(fileName)
                    .fileUrl(fileUrl)
                    .fileName(fileName.substring(fileName.lastIndexOf("/") + 1))
                    .contentType("application/pdf")
                    .fileSize((long) pdfBytes.length);

            if (receiptOpt.isPresent()) {
                docBuilder.dailyReceiptId(receiptOpt.get().getDailyReceiptId());
            }

            documentService.save(docBuilder.build());

            return fileUrl;
        } catch (Exception e) {
            log.error("Failed to generate Daily Franchise Receipt PDF: ", e);
            if (e instanceof SettlementException || e instanceof RuntimeException) {
                throw e;
            }
            throw new SettlementException(
                    SettlementErrorCode.DOCUMENT_GENERATION_FAILED);
        }
    }

    @Transactional
    public String getMonthlyFranchiseReceiptPdf(Long franchiseId,
                                                HQSettlementFranchiseMonthlyReceiptPdfRequest request) {
        try {
            // 1. 해당 가맹점의 월별 정산 데이터 조회
            java.util.Optional<com.chaing.domain.settlements.entity.MonthlySettlement> settlementOpt = monthlyService
                    .findByFranchiseAndMonth(franchiseId, request.month());

            if (settlementOpt.isPresent()) {
                MonthlySettlement settlement = settlementOpt.get();
                // 2. 이미 생성된 문서(RECEIPT_PDF)가 있는지 확인
                java.util.List<SettlementDocument> documents = documentService
                        .getMonthlyDocuments(settlement.getMonthlySettlementId());

                String existingKey = documents.stream()
                        .filter(doc -> doc
                                .getDocumentType() == DocumentType.RECEIPT_PDF)
                        .findFirst()
                        .map(SettlementDocument::getObjectKey)
                        .orElse(null);

                if (existingKey != null) {
                    return minioService.getFileUrl(existingKey, BucketName.SETTLEMENTS);
                }
            }

            // 3. 데이터가 없거나 문서가 없으면 실시간 가집계 PDF 생성 시도
            return generateProvisionalMonthlyPdf(franchiseId, request.month());
        } catch (Exception e) {
            log.error("Failed to generate Monthly Franchise Receipt PDF: ", e);
            if (e instanceof SettlementException)
                throw (SettlementException) e;
            throw new SettlementException(
                    SettlementErrorCode.DOCUMENT_GENERATION_FAILED);
        }
    }

    @Transactional
    public String getMonthlyExcel(HQSettlementMonthlyExcelRequest request) {
        try {
            // 1. 해당 월의 모든 가맹점 정산 데이터 조회
            List<com.chaing.domain.settlements.entity.MonthlySettlement> settlements = monthlyService
                    .getAllByMonth(request.month(), null);

            if (settlements.isEmpty()) {
                throw new com.chaing.domain.settlements.exception.SettlementException(
                        com.chaing.domain.settlements.exception.SettlementErrorCode.SETTLEMENT_DATA_EMPTY);
            }

            // 2. 파일 생성 서비스 호출 (Excel 생성)
            byte[] excelBytes = fileService.createMonthlySettlementExcel(settlements);

            // 3. MinIO 업로드
            String fileName = "settlement/monthly/HQ_Monthly_Settlement_" + request.month() + "_"
                    + System.currentTimeMillis() + ".xlsx";
            minioService.uploadFile(excelBytes, fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    BucketName.SETTLEMENTS);

            // 4. 메타데이터 저장
            String fileUrl = minioService.getFileUrl(fileName, BucketName.SETTLEMENTS);
            documentService.save(com.chaing.domain.settlements.entity.SettlementDocument.builder()
                    .periodType(PeriodType.MONTHLY)
                    .documentType(com.chaing.domain.settlements.enums.DocumentType.VOUCHER_EXCEL)
                    .documentOwner(com.chaing.domain.settlements.enums.DocumentOwner.HQ)
                    .settlementMonth(request.month())
                    .storageProvider("MINIO")
                    .bucket(BucketName.SETTLEMENTS.getBucketName())
                    .objectKey(fileName)
                    .fileUrl(fileUrl)
                    .fileName(fileName.substring(fileName.lastIndexOf("/") + 1))
                    .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .fileSize((long) excelBytes.length)
                    .build());

            return fileUrl;
        } catch (com.chaing.domain.settlements.exception.SettlementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate Monthly Excel: ", e);
            throw new com.chaing.domain.settlements.exception.SettlementException(
                    com.chaing.domain.settlements.exception.SettlementErrorCode.DOCUMENT_GENERATION_FAILED);
        }
    }

    // 내부 합산용 일별 정산 조회 (실시간 또는 DB)
    private AggregationResult getInternalDailyAggregation(Long franchiseId, LocalDate date) {
        if (date.equals(LocalDate.now())) {
            return aggregateDailySettlement(franchiseId, date);
        }

        return dailyService.findByFranchiseAndDate(franchiseId, date)
                .map(receipt -> new AggregationResult(receipt,
                        dailyService.getAllReceiptLines(receipt.getDailyReceiptId())))
                .orElseGet(() -> aggregateDailySettlement(franchiseId, date));
    }

    // 실시간 가집계 로직 (FranchiseSettlementFacade의 구성을 따름)
    private AggregationResult aggregateDailySettlement(Long franchiseId, LocalDate date) {
        List<DailyReceiptLine> lines = new ArrayList<>();

        // 1. 매출 집계
        List<SalesItem> salesItems = salesItemRepository.findAllBySalesFranchiseId(franchiseId).stream()
                .filter(item -> item.getCreatedAt() != null
                        && item.getCreatedAt().toLocalDate().equals(date))
                .filter(item -> item.getSales().getIsCanceled() == null
                        || !item.getSales().getIsCanceled())
                .toList();

        BigDecimal totalSale = salesItems.stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);


        // 2. 발주 집계
        List<FranchiseOrderStatus> validOrderStatuses = List.of(
                FranchiseOrderStatus.PENDING, FranchiseOrderStatus.ACCEPTED,
                FranchiseOrderStatus.PARTIAL, FranchiseOrderStatus.SHIPPING_PENDING,
                FranchiseOrderStatus.SHIPPING, FranchiseOrderStatus.COMPLETED);

        List<FranchiseOrder> orders = orderRepository.findAllByFranchiseId(franchiseId).stream()
                .filter(order -> order.getCreatedAt() != null
                        && order.getCreatedAt().toLocalDate().equals(date))
                .filter(order -> validOrderStatuses.contains(order.getOrderStatus()))
                .toList();

        BigDecimal orderAmount = BigDecimal.ZERO;
        if (!orders.isEmpty()) {
            orderAmount = orderItemRepository
                    .findAllByFranchiseOrder_FranchiseOrderIdInAndDeletedAtIsNull(
                            orders.stream().map(FranchiseOrder::getFranchiseOrderId)
                                    .toList())
                    .stream()
                    .map(FranchiseOrderItem::getTotalPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        // 3. 반품/손실 집계
        List<ReturnStatus> validReturnStatuses = List.of(
                ReturnStatus.PENDING, ReturnStatus.ACCEPTED, ReturnStatus.SHIPPING_PENDING,
                ReturnStatus.SHIPPING, ReturnStatus.COMPLETED, ReturnStatus.INSPECTING,
                ReturnStatus.DEDUCTION_COMPLETED);

        List<Returns> returnsList = returnRepository.findAllByFranchiseIdAndDeletedAtIsNull(franchiseId)
                .stream()
                .filter(ret -> ret.getCreatedAt() != null
                        && ret.getCreatedAt().toLocalDate().equals(date))
                .filter(ret -> validReturnStatuses.contains(ret.getReturnStatus()))
                .toList();

        BigDecimal refundAmount = BigDecimal.ZERO;
        BigDecimal lossAmount = BigDecimal.ZERO;

        for (Returns ret : returnsList) {
            List<ReturnItem> items = returnItemRepository
                    .findAllByReturns_ReturnIdInAndDeletedAtIsNull(List.of(ret.getReturnId()));
            BigDecimal returnTotal = BigDecimal.ZERO;
            for (ReturnItem item : items) {
                returnTotal = returnTotal.add(orderItemRepository
                        .findById(item.getFranchiseOrderItemId())
                        .map(FranchiseOrderItem::getUnitPrice).orElse(BigDecimal.ZERO));
            }
            if (ret.getReturnType() == ReturnType.PRODUCT_DEFECT) {
                refundAmount = refundAmount.add(returnTotal);
            } else {
                lossAmount = lossAmount.add(returnTotal);
            }
        }

        // 4. 배송비 집계

        BigDecimal deliveryFee = BigDecimal.ZERO;
                /*List<DeliverStatus> validDeliverStatuses = List.of(DeliverStatus.IN_TRANSIT, DeliverStatus.DELIVERED);
                List<Transit> transits = transitRepository.findByFranchiseId(franchiseId).stream()
                                .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().toLocalDate().equals(date))
                                .filter(t -> validDeliverStatuses.contains(t.getStatus()))
                                .toList();

                BigDecimal deliveryFee = BigDecimal.ZERO;
                if (!transits.isEmpty()) {
                        Map<String, List<Transit>> grouped = transits.stream()
                                        .collect(Collectors.groupingBy(Transit::getTrackingNumber));
                        for (Map.Entry<String, List<Transit>> entry : grouped.entrySet()) {
                                List<OrderInfo> orderInfos = entry.getValue().stream()
                                                .map(t -> new OrderInfo(null, t.getOrderCode(), t.getWeight(),
                                                                t.getFranchiseId(), null, null))
                                                .toList();
                                List<DeliveryFeeInfo> fees = transportService.calculateDeliveryFee(orderInfos,
                                                entry.getValue().get(0).getVehicleId());
                                for (DeliveryFeeInfo fee : fees) {
                                        if (fee.franchiseId().equals(franchiseId)) {
                                                deliveryFee = deliveryFee.add(fee.deliveryFee());
                                        }
                                }
                        }
                }*/

        // 5. 수수료 (3.3%)
        BigDecimal commissionFee = totalSale.multiply(new BigDecimal("0.033"))
                .setScale(0, java.math.RoundingMode.HALF_UP);

        // 정산 객체 생성
        DailySettlementReceipt receipt = DailySettlementReceipt.builder()
                .franchiseId(franchiseId)
                .settlementDate(date)
                .totalSaleAmount(totalSale)
                .orderAmount(orderAmount)
                .refundAmount(refundAmount)
                .lossAmount(lossAmount)
                .deliveryFee(deliveryFee)
                .commissionFee(commissionFee)
                .adjustmentAmount(BigDecimal.ZERO)
                .finalAmount(totalSale
                        .subtract(orderAmount.add(lossAmount).add(commissionFee)
                                .add(deliveryFee))
                        .add(refundAmount))
                .build();

        return new AggregationResult(receipt, lines);
    }

    private String generateProvisionalMonthlyPdf(Long franchiseId, YearMonth month) {
        // 1. 해당 월의 모든 날짜를 순회하며 데이터 수집 (확정 + 실시간 가집계)
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        LocalDate today = LocalDate.now();
        if (end.isAfter(today)) {
            end = today;
        }

        List<com.chaing.domain.settlements.entity.DailySettlementReceipt> receipts = new ArrayList<>();
        List<SettlementVoucher> vouchers = new ArrayList<>();

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            AggregationResult result = aggregateDailySettlement(franchiseId, date);
            com.chaing.domain.settlements.entity.DailySettlementReceipt receipt = result.receipt();

            // 의미 있는 데이터가 있는 날만 포함
            if (receipt.getTotalSaleAmount().compareTo(BigDecimal.ZERO) > 0 ||
                    receipt.getOrderAmount().compareTo(BigDecimal.ZERO) > 0 ||
                    receipt.getDeliveryFee().compareTo(BigDecimal.ZERO) > 0) {

                receipts.add(receipt);

                vouchers.add(SettlementVoucher.builder()
                        .voucherType(VoucherType.SALES)
                        .amount(receipt.getFinalAmount())
                        .description(date + " 일별 정산 합계")
                        .occurredAt(date.atStartOfDay())
                        .build());
            }
        }

        if (receipts.isEmpty()) {
            throw new com.chaing.domain.settlements.exception.SettlementException(
                    com.chaing.domain.settlements.exception.SettlementErrorCode.SETTLEMENT_DATA_EMPTY);
        }

        // 2. 가집계 MonthlySettlement 객체 생성
        com.chaing.domain.settlements.entity.MonthlySettlement provisionalSettlement = aggregateMonthlySettlement(
                franchiseId, month, receipts);

        // 가맹점명 조회
        String franchiseName = franchiseRepository.findById(franchiseId)
                .map(com.chaing.domain.businessunits.entity.Franchise::getName)
                .orElse("Unknown Store");

        // 3. PDF 생성
        byte[] pdfBytes = fileService.createMonthlyReceiptPdf(provisionalSettlement, vouchers, franchiseName);

        // 4. MinIO 업로드
        String fileName = "settlement/provisional/FR_" + franchiseId + "_" + month + "_Preview_"
                + System.currentTimeMillis() + ".pdf";
        minioService.uploadFile(pdfBytes, fileName, "application/pdf", BucketName.SETTLEMENTS);
        String fileUrl = minioService.getFileUrl(fileName, BucketName.SETTLEMENTS);

        // 5. DB에 메타데이터 저장 (추적용)
        documentService.save(com.chaing.domain.settlements.entity.SettlementDocument.builder()
                .periodType(PeriodType.MONTHLY)
                .documentType(com.chaing.domain.settlements.enums.DocumentType.PROVISIONAL_RECEIPT_PDF)
                .documentOwner(com.chaing.domain.settlements.enums.DocumentOwner.FRANCHISE)
                .franchiseId(franchiseId)
                .storageProvider("MINIO")
                .bucket(BucketName.SETTLEMENTS.getBucketName())
                .objectKey(fileName)
                .fileUrl(fileUrl)
                .fileName(fileName.substring(fileName.lastIndexOf("/") + 1))
                .contentType("application/pdf")
                .fileSize((long) pdfBytes.length)
                .build());

        return fileUrl;
    }

    private com.chaing.domain.settlements.entity.MonthlySettlement aggregateMonthlySettlement(Long franchiseId,
                                                                                              YearMonth month,
                                                                                              List<com.chaing.domain.settlements.entity.DailySettlementReceipt> dailyReceipts) {
        BigDecimal totalSale = dailyReceipts.stream()
                .map(com.chaing.domain.settlements.entity.DailySettlementReceipt::getTotalSaleAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal orderAmount = dailyReceipts.stream()
                .map(com.chaing.domain.settlements.entity.DailySettlementReceipt::getOrderAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commissionFee = dailyReceipts.stream()
                .map(com.chaing.domain.settlements.entity.DailySettlementReceipt::getCommissionFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deliveryFee = dailyReceipts.stream()
                .map(com.chaing.domain.settlements.entity.DailySettlementReceipt::getDeliveryFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lossAmount = dailyReceipts.stream()
                .map(com.chaing.domain.settlements.entity.DailySettlementReceipt::getLossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refundAmount = dailyReceipts.stream()
                .map(com.chaing.domain.settlements.entity.DailySettlementReceipt::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal adjustmentAmount = dailyReceipts.stream()
                .map(com.chaing.domain.settlements.entity.DailySettlementReceipt::getAdjustmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal finalAmount = dailyReceipts.stream()
                .map(com.chaing.domain.settlements.entity.DailySettlementReceipt::getFinalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return com.chaing.domain.settlements.entity.MonthlySettlement.builder()
                .franchiseId(franchiseId)
                .settlementMonth(month)
                .totalSaleAmount(totalSale)
                .orderAmount(orderAmount)
                .commissionFee(commissionFee)
                .deliveryFee(deliveryFee)
                .lossAmount(lossAmount)
                .refundAmount(refundAmount)
                .adjustmentAmount(adjustmentAmount)
                .finalSettlementAmount(finalAmount)
                .status(com.chaing.domain.settlements.enums.SettlementStatus.DRAFT) // 가집계용 상태
                .build();
    }


    // 상세 내역 (가맹점 페이지 연동용)

    @Transactional(readOnly = true)
    public List<FranchiseSalesItemResponse> getDailySalesItems(Long franchiseId, LocalDate date, Integer limit) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);
        List<SalesItem> items = salesItemRepository.findAllBySalesFranchiseIdAndCreatedAtBetween(franchiseId, start, end)
                .stream()
                .filter(item -> item.getSales().getIsCanceled() == null || !item.getSales().getIsCanceled())
                .collect(Collectors.toList());
        return aggregateSalesItems(items, limit);
    }

    @Transactional(readOnly = true)
    public List<FranchiseOrderItemResponse> getDailyOrderItems(Long franchiseId, LocalDate date, Integer limit) {
        List<FranchiseOrderStatus> validStatuses = List.of(
                FranchiseOrderStatus.PENDING, FranchiseOrderStatus.ACCEPTED,
                FranchiseOrderStatus.PARTIAL, FranchiseOrderStatus.SHIPPING_PENDING,
                FranchiseOrderStatus.SHIPPING, FranchiseOrderStatus.COMPLETED);
        List<FranchiseOrder> orders = orderRepository.findAllByFranchiseIdAndOrderStatusInAndCreatedAtBetween(
                franchiseId, validStatuses, date.atStartOfDay(), date.atTime(LocalTime.MAX));
        List<Long> orderIds = orders.stream().map(FranchiseOrder::getFranchiseOrderId).collect(Collectors.toList());
        List<FranchiseOrderItem> items = orderItemRepository.findAllByFranchiseOrderFranchiseOrderIdIn(orderIds);
        return aggregateOrderItems(items, limit);
    }

    @Transactional(readOnly = true)
    public List<FranchiseSalesItemResponse> getMonthlySalesItems(Long franchiseId, YearMonth month, Integer limit) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.atEndOfMonth().atTime(LocalTime.MAX);
        List<SalesItem> items = salesItemRepository.findAllBySalesFranchiseIdAndCreatedAtBetween(franchiseId, start, end)
                .stream()
                .filter(item -> item.getSales().getIsCanceled() == null || !item.getSales().getIsCanceled())
                .collect(Collectors.toList());
        return aggregateSalesItems(items, limit);
    }

    @Transactional(readOnly = true)
    public List<FranchiseOrderItemResponse> getMonthlyOrderItems(Long franchiseId, YearMonth month, Integer limit) {
        List<FranchiseOrderStatus> validStatuses = List.of(
                FranchiseOrderStatus.PENDING, FranchiseOrderStatus.ACCEPTED,
                FranchiseOrderStatus.PARTIAL, FranchiseOrderStatus.SHIPPING_PENDING,
                FranchiseOrderStatus.SHIPPING, FranchiseOrderStatus.COMPLETED);
        List<FranchiseOrder> orders = orderRepository.findAllByFranchiseIdAndOrderStatusInAndCreatedAtBetween(
                franchiseId, validStatuses, month.atDay(1).atStartOfDay(), month.atEndOfMonth().atTime(LocalTime.MAX));
        List<Long> orderIds = orders.stream().map(FranchiseOrder::getFranchiseOrderId).collect(Collectors.toList());
        List<FranchiseOrderItem> items = orderItemRepository.findAllByFranchiseOrderFranchiseOrderIdIn(orderIds);
        return aggregateOrderItems(items, limit);
    }

    @Transactional(readOnly = true)
    public List<FranchiseDailyGraphResponse> getMonthlySalesTrend(Long franchiseId, YearMonth month) {
        LocalDate startDay = month.atDay(1);
        LocalDate endDay = month.atEndOfMonth();
        if (month.equals(YearMonth.now())) {
            endDay = LocalDate.now();
        }

        List<FranchiseDailyGraphResponse> trend = new ArrayList<>();
        for (LocalDate date = startDay; !date.isAfter(endDay); date = date.plusDays(1)) {
            AggregationResult result = getInternalDailyAggregation(franchiseId, date);
            trend.add(new FranchiseDailyGraphResponse(date, result.receipt().getTotalSaleAmount()));
        }
        return trend;
    }

    private List<FranchiseSalesItemResponse> aggregateSalesItems(List<SalesItem> items, Integer limit) {
        Map<String, List<SalesItem>> grouped = items.stream()
                .collect(Collectors.groupingBy(SalesItem::getProductName));
        List<FranchiseSalesItemResponse> result = grouped.entrySet().stream()
                .map(entry -> {
                    String name = entry.getKey();
                    List<SalesItem> group = entry.getValue();
                    int totalQty = group.stream().mapToInt(SalesItem::getQuantity).sum();
                    BigDecimal total = group.stream()
                            .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal displayPrice = group.get(0).getUnitPrice();
                    return new FranchiseSalesItemResponse(0, name, totalQty, displayPrice, total);
                })
                .sorted((a, b) -> b.totalSales().compareTo(a.totalSales()))
                .collect(Collectors.toList());

        List<FranchiseSalesItemResponse> ranked = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            if (limit != null && i >= limit)
                break;
            var item = result.get(i);
            ranked.add(new FranchiseSalesItemResponse(
                    i + 1, item.productName(), item.totalQuantity(), item.unitPrice(), item.totalSales()));
        }
        return ranked;
    }

    private List<FranchiseOrderItemResponse> aggregateOrderItems(List<FranchiseOrderItem> items, Integer limit) {
        Map<Long, List<FranchiseOrderItem>> grouped = items.stream()
                .collect(Collectors.groupingBy(FranchiseOrderItem::getProductId));

        List<Long> productIds = new ArrayList<>(grouped.keySet());
        Map<Long, String> productNames = productRepository.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getProductId, Product::getName));

        List<FranchiseOrderItemResponse> result = grouped.entrySet().stream()
                .map(entry -> {
                    Long productId = entry.getKey();
                    String productName = productNames.getOrDefault(productId, "알 수 없는 상품 (ID: " + productId + ")");
                    List<FranchiseOrderItem> group = entry.getValue();
                    int totalQty = group.stream().mapToInt(FranchiseOrderItem::getQuantity).sum();
                    BigDecimal total = group.stream()
                            .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal displayPrice = group.get(0).getUnitPrice();
                    return new FranchiseOrderItemResponse(0, productName, totalQty, displayPrice, total);
                })
                .sorted((a, b) -> b.totalAmount().compareTo(a.totalAmount()))
                .collect(Collectors.toList());

        List<FranchiseOrderItemResponse> ranked = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            if (limit != null && i >= limit)
                break;
            var item = result.get(i);
            ranked.add(new FranchiseOrderItemResponse(
                    i + 1, item.productName(), item.totalQuantity(), item.unitPrice(), item.totalAmount()));
        }
        return ranked;
    }

    private record AggregationResult(DailySettlementReceipt receipt, List<DailyReceiptLine> lines) {
    }

    // --- Batch Processing Methods ---

    @Transactional
    public void createDailySnapshot(LocalDate date) {
        log.info("Starting Daily Settlement Snapshot Batch for all franchises on date: {}", date);
        List<Franchise> franchises = franchiseRepository.findAll();
        int successCount = 0;
        for (Franchise franchise : franchises) {
            try {
                AggregationResult result = aggregateDailySettlement(franchise.getFranchiseId(), date);
                dailyService.save(result.receipt());
                successCount++;
            } catch (Exception e) {
                log.error("Failed to create daily snapshot for franchise: {}", franchise.getFranchiseId(), e);
            }
        }
        log.info("Daily Settlement Snapshot Batch completed. Success: {}/{}", successCount, franchises.size());
        if (successCount > 0) {
            redisCacheHelper.evictByPattern("settlement:hq:*");
        }
    }
        @Transactional
        public void generateMonthlyReports (YearMonth month) {
            log.info("Starting Monthly Settlement Report Batch for all franchises for month: {}", month);
            List<Franchise> franchises = franchiseRepository.findAll();
            int successCount = 0;
            for (Franchise franchise : franchises) {
                try {
                    // 월간 정산 데이터 집계 및 PDF 생성
                    generateOfficialMonthlyReport(franchise.getFranchiseId(), month);
                    successCount++;
                } catch (Exception e) {
                    log.error("Failed to generate monthly report for franchise: {}", franchise.getFranchiseId(), e);
                }
            }
            log.info("Monthly Settlement Report Batch completed. Success: {}/{}", successCount, franchises.size());
            if (successCount > 0) {
                redisCacheHelper.evictByPattern("settlement:hq:*");
            }
        }

            private void generateOfficialMonthlyReport (Long franchiseId, YearMonth month){
                // 1. 해당 월의 일별 데이터 조회
                LocalDate start = month.atDay(1);
                LocalDate end = month.atEndOfMonth();
                List<DailySettlementReceipt> receipts = dailyService.getAllByFranchiseAndDateRange(franchiseId, start, end);

                if (receipts.isEmpty()) {
                    log.warn("No settlement data found for franchise {} in month {}", franchiseId, month);
                    return;
                }

                // 2. 월간 정산 객체 생성
                MonthlySettlement monthlySettlement = aggregateMonthlySettlement(franchiseId, month, receipts);
                monthlySettlement = monthlyService.save(monthlySettlement);

                // 3. 바우처(전표) 목록 생성
                List<SettlementVoucher> vouchers = receipts.stream()
                        .map(r -> SettlementVoucher.builder()
                                .voucherType(VoucherType.SALES)
                                .amount(r.getFinalAmount())
                                .description(r.getSettlementDate() + " 일별 정산 합계")
                                .occurredAt(r.getSettlementDate().atStartOfDay())
                                .build())
                        .collect(Collectors.toList());

                // 4. 가맹점명 조회
                String franchiseName = franchiseRepository.findById(franchiseId)
                        .map(Franchise::getName)
                        .orElse("Unknown Store");

                // 5. PDF 생성 및 업로드
                byte[] pdfBytes = fileService.createMonthlyReceiptPdf(monthlySettlement, vouchers, franchiseName);
                String pdfFileName = "settlement/reports/FR_" + franchiseId + "_" + month + ".pdf";
                minioService.uploadFile(pdfBytes, pdfFileName, "application/pdf", BucketName.SETTLEMENTS);
                String pdfUrl = minioService.getFileUrl(pdfFileName, BucketName.SETTLEMENTS);

                // 6. DB에 문서 메타데이터 저장
                documentService.save(SettlementDocument.builder()
                        .periodType(PeriodType.MONTHLY)
                        .documentType(DocumentType.RECEIPT_PDF)
                        .documentOwner(DocumentOwner.FRANCHISE)
                        .franchiseId(franchiseId)
                        .monthlySettlementId(monthlySettlement.getMonthlySettlementId())
                        .storageProvider("MINIO")
                        .bucket(BucketName.SETTLEMENTS.getBucketName())
                        .objectKey(pdfFileName)
                        .fileUrl(pdfUrl)
                        .fileName(pdfFileName.substring(pdfFileName.lastIndexOf("/") + 1))
                        .contentType("application/pdf")
                        .fileSize((long) pdfBytes.length)
                        .build());
            }

            private <T > T readObjectCache(String key, Class < T > clazz) {
                try {
                    String cached = redisTemplate.opsForValue().get(key);
                    if (cached == null) return null;
                    return objectMapper.readValue(cached, clazz);
                } catch (Exception e) {
                    return null;
                }
            }

            private void writeCache (String key, Object value){
                try {
                    redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), CACHE_TTL);
                } catch (Exception ignored) {

                }
            }
        }


