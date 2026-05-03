package com.chaing.domain.settlements.service;

import com.chaing.domain.settlements.entity.SettlementAdjustment;
import com.chaing.domain.settlements.repository.interfaces.SettlementAdjustmentRepository;
import com.chaing.domain.settlements.service.impl.SettlementAdjustmentServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.chaing.domain.settlements.enums.VoucherType;
import com.chaing.domain.settlements.exception.SettlementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementAdjustmentServiceTests {

    @Mock
    private SettlementAdjustmentRepository repository;
    @InjectMocks
    private SettlementAdjustmentServiceImpl adjustmentService;

    @DisplayName("새로운 조정 내역 생성(저장)")
    @Test
    void createAdjustment_Success() {
        // given
        SettlementAdjustment adjustment = SettlementAdjustment.builder()
                .adjustmentAmount(new BigDecimal("5000"))
                .reason("배송 지연 보상")
                .build();

        when(repository.save(adjustment)).thenReturn(adjustment);
        // when
        SettlementAdjustment result = adjustmentService.create(adjustment);
        // then
        assertThat(result.getAdjustmentAmount()).isEqualTo(new BigDecimal("5000"));
        assertThat(result.getReason()).isEqualTo("배송 지연 보상");
        verify(repository).save(adjustment);
    }

    @DisplayName("조정 내역 생성 - 실패 (데이터 무결성 위반)")
    @Test
    void createAdjustment_Fail_DataIntegrityViolation() {
        // given
        SettlementAdjustment adjustment = SettlementAdjustment.builder()
                .adjustmentAmount(new BigDecimal("5000"))
                .build();

        when(repository.save(adjustment)).thenThrow(new DataIntegrityViolationException("DB Error"));

        // when, then
        assertThatThrownBy(() -> adjustmentService.create(adjustment))
                .isInstanceOf(SettlementException.class)
                .hasMessageContaining("유효하지 않은 조정 전표 데이터입니다");
    }

    @DisplayName("조정 내역 목록 페이징 및 필터 조회 - 성공")
    @Test
    void getAll_Success() {
        // given
        Long franchiseId = 1L;
        VoucherType type = VoucherType.LOSS;
        Pageable pageable = PageRequest.of(0, 10);

        SettlementAdjustment mockAdjustment = SettlementAdjustment.builder()
                .settlementAdjustmentId(100L)
                .voucherType(type)
                .build();
        Page<SettlementAdjustment> mockPage = new PageImpl<>(List.of(mockAdjustment));

        when(repository.findByConditions(franchiseId, type, null, pageable)).thenReturn(mockPage);

        // when
        Page<SettlementAdjustment> result = adjustmentService.getAll(franchiseId, type, null, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getSettlementAdjustmentId()).isEqualTo(100L);
        verify(repository).findByConditions(franchiseId, type, null, pageable);
    }

    @DisplayName("특정 조정 내역 단건 조회 - 실패 (데이터 없음)")
    @Test
    void getById_Fail_NotFound() {
        // given
        Long id = 999L;
        when(repository.findById(id)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> adjustmentService.getById(id))
                .isInstanceOf(SettlementException.class)
                .hasMessageContaining("해당 조정 전표를 찾을 수 없습니다.");
    }

}