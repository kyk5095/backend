package com.chaing.api.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 정산 요약 조회를 위한 직접 구현한 LRU (Least Recently Used) 캐시 자료구조
 * - 양방향 연결 리스트(Doubly Linked List) + 해시 맵(Hash Map) 조합
 * - get() 및 put() 연산 모두 O(1) 시간 복잡도 보장
 * - 동시성 제어를 위해 메서드 단위 synchronized 적용 (Thread-Safe)
 */
public class LruCache<K, V> {

    // 1. 양방향 노드 정의 (prev, next 포인터 직접 관리)
    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity; // 최대 보관 개수
    private final Map<K, Node<K, V>> map; // O(1) 탐색용 해시 테이블
    private final Node<K, V> head; // 가장 최근에 사용된 위치 (더미 노드)
    private final Node<K, V> tail; // 가장 오래되어 버려질 위치 (더미 노드)

    public LruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>();

        // Null Pointer 방지를 위한 더미(Sentinel) 노드 상호 연결
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * 조회: O(1)
     * 데이터가 있으면 최근 사용 위치(Head)로 이동 후 값 반환
     */
    public synchronized V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null; // Cache Miss
        }
        moveToHead(node); // 최근 사용되었으므로 맨 앞으로 이동
        return node.value; // Cache Hit
    }

    /**
     * 저장/갱신: O(1)
     * 이미 있으면 값 갱신 후 맨 앞으로 이동.
     * 새로운 데이터면 맨 앞에 추가하고, 용량 초과 시 가장 오래된 노드(Tail)를 O(1)로 제거.
     */
    public synchronized void put(K key, V value) {
        Node<K, V> node = map.get(key);

        if (node != null) {
            node.value = value;
            moveToHead(node);
        } else {
            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);

            // 용량 초과 시 가장 오래된 노드 제거
            if (map.size() > capacity) {
                Node<K, V> lruNode = removeTail();
                map.remove(lruNode.key); // 메모리 해제
            }
        }
    }

    // --- 포인터 직접 조작 로직 ---

    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    private Node<K, V> removeTail() {
        Node<K, V> lru = tail.prev;
        removeNode(lru);
        return lru;
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized void clear() {
        map.clear();
        head.next = tail;
        tail.prev = head;
    }
}
