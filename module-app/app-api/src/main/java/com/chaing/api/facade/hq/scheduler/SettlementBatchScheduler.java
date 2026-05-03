package com.chaing.api.facade.hq.scheduler;

import com.chaing.api.facade.hq.HQSettlementFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementBatchScheduler {

    private final HQSettlementFacade hqSettlementFacade;

    /**
     * Daily Snapshot Batch
     * 매일 자정(00:00)에 어제(D-1)의 정산 데이터를 집계하여 테이블에 고정합니다.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void runDailySettlementSnapshot() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Starting Daily Settlement Snapshot Batch for: {}", yesterday);
        
        try {
            hqSettlementFacade.createDailySnapshot(yesterday);
            log.info("Daily Settlement Snapshot Batch completed for: {}", yesterday);
        } catch (Exception e) {
            log.error("Error during Daily Settlement Snapshot Batch: ", e);
        }
    }

    /**
     * Monthly Report Batch
     * 매월 1일 새벽 2시에 지난달의 월간 정산서(PDF/Excel)를 미리 생성합니다.
     */
    @Scheduled(cron = "0 0 2 1 * *")
    public void runMonthlySettlementReport() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        log.info("Starting Monthly Settlement Report Batch for: {}", lastMonth);

        try {
            hqSettlementFacade.generateMonthlyReports(lastMonth);
            log.info("Monthly Settlement Report Batch completed for: {}", lastMonth);
        } catch (Exception e) {
            log.error("Error during Monthly Settlement Report Batch: ", e);
        }
    }
}
