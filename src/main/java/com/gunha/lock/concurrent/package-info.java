/**
 * [3] 동기화 도구와 원자 변수: {@code java.util.concurrent}, {@code java.util.concurrent.atomic}.
 *
 * <h2>무엇인가</h2>
 * 상호 배제(한 번에 하나)가 아니라 "몇 개까지", "몇 개가 끝날 때까지", "모두 모일 때까지"처럼
 * 스레드 사이의 <b>조율</b>을 표현하는 도구들이다. 락으로 직접 만들 수 있지만, 검증된 구현을 쓰는 편이 안전하다.
 * 원자 변수는 반대로 아예 <b>락 없이</b>(lock-free) CAS 명령으로 동시성을 해결한다.
 *
 * <h2>구현 관점</h2>
 * {@linkplain com.gunha.lock.locks##formula locks 패키지의 공통 공식}("{@code state} CAS + 큐 + park")으로 보면 이렇게 나뉜다.
 * <ul>
 * <li>원자 변수({@code Atomic*}, {@code LongAdder}): 공식에서 <b>큐와 park를 뺀 것</b>이다. CPU의 원자 명령(CAS, fetch-and-add)만 쓰고,
 *     CAS가 실패하면 잠들지 않고 바로 다시 시도한다. 그래서 대기 상태가 없고 OS도 관여하지 않지만,
 *     경합이 심하면 CPU를 계속 쓴다({@link com.gunha.lock.concurrent.AtomicIntegerWrapper}의 재시도 횟수).</li>
 * <li>{@code Semaphore}, {@code CountDownLatch}: 공식 그대로이고, AQS의 <b>공유 모드</b>다. {@code state}가 각각 남은 허가 수,
 *     남은 카운트를 뜻한다. 하나가 풀리면 뒤따르는 대기자들도 연쇄적으로 깨어난다(propagation).</li>
 * <li>{@code CyclicBarrier}: AQS를 직접 쓰지 않고, 공식으로 만든 {@code ReentrantLock + Condition}을 한 번 더 조합했다.</li>
 * </ul>
 *
 * <h2>예제</h2>
 * 예제마다 API 하나를 감싼 래퍼이고, 이름은 감싼 API 이름 뒤에 {@code Wrapper}를 붙였다.
 * <ol>
 * <li>{@link com.gunha.lock.concurrent.AtomicIntegerWrapper}: {@code AtomicInteger}와 CAS 재시도 루프</li>
 * <li>{@link com.gunha.lock.concurrent.LongAdderWrapper}: 경합이 심한 카운터용 {@code LongAdder}</li>
 * <li>{@link com.gunha.lock.concurrent.SemaphoreWrapper}: {@code Semaphore}로 동시 실행 수 제한</li>
 * <li>{@link com.gunha.lock.concurrent.CountDownLatchWrapper}: {@code CountDownLatch}로 시작 신호와 완료 대기</li>
 * <li>{@link com.gunha.lock.concurrent.CyclicBarrierWrapper}: {@code CyclicBarrier}로 라운드마다 모두 모이기</li>
 * </ol>
 */
package com.gunha.lock.concurrent;
