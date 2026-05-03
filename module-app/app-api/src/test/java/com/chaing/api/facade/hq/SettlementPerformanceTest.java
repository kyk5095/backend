package com.chaing.api.facade.hq;

import com.chaing.api.dto.hq.settlement.request.HQSettlementMonthlySummaryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StopWatch;

import java.time.YearMonth;
import java.time.LocalTime;

import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "jwt.secret=ZGVmYXVsdC10ZXN0LXNlY3JldC1rZXktZm9yLWp3dC10b2tlbi1zaWduYXR1cmU=",
    "jwt.expiration=3600000",
    "jwt.refresh-expiration=86400000",
    "spring.datasource.url=jdbc:h2:mem:testdb;NON_KEYWORDS=USER",
    "minio.endpoint=http://localhost:9000",
    "minio.external-url=http://localhost:9000",
    "minio.access-key=admin",
    "minio.secret-key=adminpassword",
    "app.url.login=http://localhost:8080/login",
    "app.mail.from=noreply@chaing.com"
})
public class SettlementPerformanceTest {

    @MockBean
    private JavaMailSender javaMailSender;

    @Autowired
    private HQSettlementFacade hqSettlementFacade;

    @Autowired
    private com.chaing.domain.businessunits.repository.FranchiseRepository franchiseRepository;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        franchiseRepository.deleteAll();

        com.chaing.domain.businessunits.entity.Franchise franchise = com.chaing.domain.businessunits.entity.Franchise.builder()
                .name("테스트 가맹점")
                .businessNumber("123-45-67890")
                .franchiseCode("FR001")
                .representativeName("윤경님")
                .address("서울시 강남구")
                .phone("010-1234-5678")
                .region(com.chaing.core.enums.Region.SEOUL)
                .status(com.chaing.core.enums.UsableStatus.ACTIVE)
                .openTime(LocalTime.of(9, 0))
                .closeTime(LocalTime.of(22, 0))
                .operatingDays("MON,TUE,WED,THU,FRI")
                .distanceToFactory(10.5)
                .build();
        
        franchiseRepository.save(franchise);
        System.out.println(">>> [SETUP] 테스트용 가맹점 데이터가 생성되었습니다.");
    }

    @Test
    void measureMonthlySettlementTime() {
        YearMonth targetMonth = YearMonth.now();
        HQSettlementMonthlySummaryRequest request = new HQSettlementMonthlySummaryRequest(targetMonth);

        System.out.println("\n" + "=".repeat(50));
        System.out.println("   [성능 테스트] 정산 집계 방식 비교 시작");
        System.out.println("=".repeat(50));

        StopWatch stopWatch = new StopWatch();

        System.out.println("\n>>> [STEP 1] 실시간 합산 방식(기존) 측정 시작...");
        stopWatch.start("Real-time Aggregation");
        hqSettlementFacade.getMonthlySummary(request);
        stopWatch.stop();
        long realTimeMillis = stopWatch.getLastTaskTimeMillis();
        System.out.println("실시간 방식 소요 시간: " + realTimeMillis + " ms");

        System.out.println("\n>>> [STEP 2] 배치(Batch) 로직 가동... (데이터 사전 집계 중)");
        for (int day = 1; day <= targetMonth.lengthOfMonth(); day++) {
            hqSettlementFacade.createDailySnapshot(targetMonth.atDay(day));
        }
        hqSettlementFacade.generateMonthlyReports(targetMonth);
        System.out.println("배치 작업 완료!");

        System.out.println("\n>>> [STEP 3] 배치 결과 조회 방식(최적화) 측정 시작...");
        stopWatch.start("Batch Result Lookup");
        hqSettlementFacade.getMonthlySummary(request);
        stopWatch.stop();
        long batchTimeMillis = stopWatch.getLastTaskTimeMillis();
        System.out.println("배치 조회 방식 소요 시간: " + batchTimeMillis + " ms");

        System.out.println("\n" + "=".repeat(50));
        System.out.println("   [최종 결과 리포트]");
        System.out.println("=".repeat(50));
        System.out.println("- 실시간 방식: " + realTimeMillis + " ms");
        System.out.println("- 배치 방식: " + batchTimeMillis + " ms");
        
        if (realTimeMillis > batchTimeMillis) {
            double improvement = ((double)(realTimeMillis - batchTimeMillis) / realTimeMillis) * 100;
            System.out.printf("성능 개선율: %.2f%% 단축되었습니다! 🎉\n", improvement);
        }
        System.out.println("=".repeat(50) + "\n");
    }
}
