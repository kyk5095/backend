package com.chaing.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class LruCacheTest {

    @Test
    @DisplayName("기본 삽입 및 조회 테스트")
    void testBasicPutAndGet() {
        LruCache<String, String> cache = new LruCache<>(2);

        cache.put("key1", "value1");
        cache.put("key2", "value2");

        assertThat(cache.get("key1")).isEqualTo("value1");
        assertThat(cache.get("key2")).isEqualTo("value2");
        assertThat(cache.get("key3")).isNull(); // 없는 키는 null
    }

    @Test
    @DisplayName("용량 초과 시 가장 오랫동안 참조되지 않은 항목(LRU)이 삭제되어야 한다")
    void testLruEviction() {
        // 용량 3인 캐시 생성
        LruCache<String, Integer> cache = new LruCache<>(3);

        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);
        // 현재 상태 (최신순): C -> B -> A

        // A를 조회 (A가 방금 사용되었으므로 최신 위치로 올라옴!)
        cache.get("A");
        // 현재 상태 (최신순): A -> C -> B (B가 가장 오래된 녀석이 됨)

        // 새로운 데이터 D 삽입 -> 용량 초과로 가장 오래된 B가 삭제되어야 함!
        cache.put("D", 4);

        assertThat(cache.get("B")).isNull(); // B는 삭제됨 (Evicted)
        assertThat(cache.get("A")).isEqualTo(1); // A는 살아있음
        assertThat(cache.get("C")).isEqualTo(3); // C는 살아있음
        assertThat(cache.get("D")).isEqualTo(4); // D는 살아있음
        assertThat(cache.size()).isEqualTo(3); // 용량 3 유지
    }

    @Test
    @DisplayName("기존 키의 값을 수정하면 최신 위치로 올라오고 값이 갱신된다")
    void testUpdateExistingKey() {
        LruCache<String, String> cache = new LruCache<>(2);

        cache.put("A", "old");
        cache.put("B", "valueB");

        // A의 값을 갱신 -> A가 최신 위치로 올라옴
        cache.put("A", "new");

        // C 삽입 -> B가 가장 오래되었으므로 B가 삭제되어야 함
        cache.put("C", "valueC");

        assertThat(cache.get("A")).isEqualTo("new");
        assertThat(cache.get("B")).isNull(); // B 삭제됨
        assertThat(cache.get("C")).isEqualTo("valueC");
    }

    @Test
    @DisplayName("여러 스레드가 동시에 접근해도 데이터가 안전하게 보장된다")
    void testConcurrency() throws InterruptedException {
        int capacity = 50;
        LruCache<Integer, Integer> cache = new LruCache<>(capacity);
        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        int key = threadId * 100 + j;
                        cache.put(key, key);
                        cache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // 1000개의 데이터가 들어갔어도 최대 크기는 정확히 50을 초과하지 않아야 함
        assertThat(cache.size()).isEqualTo(capacity);
    }
}
