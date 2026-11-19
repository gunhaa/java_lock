/**
 * [2] 명시적 락: {@code java.util.concurrent.locks} 패키지.
 *
 * <h2>무엇인가</h2>
 * 모니터와 달리 JVM 명령이 아니라 <b>자바 코드로 구현된 라이브러리</b>다(Doug Lea, JSR 166, Java 5).
 * 핵심은 거의 모든 구현이 공유하는 뼈대인 {@link java.util.concurrent.locks.AbstractQueuedSynchronizer}(AQS)다.
 *
 * <h2>AQS 구조 (JVM 관점)</h2>
 * <ul>
 * <li>{@code volatile int state}: 락 상태. 의미는 구현이 정한다(ReentrantLock은 보유 횟수, Semaphore는 남은 허가 수).</li>
 * <li>획득 시도 = {@code state}에 대한 CAS. JIT이 x86 {@code lock cmpxchg}, ARM64 {@code casal}(또는 LL/SC) 명령으로 바꾼다.
 *     경합이 없으면 이 한 명령으로 끝난다.</li>
 * <li>실패하면 대기 큐(CLH 큐를 변형한 이중 연결 리스트)에 노드로 들어가 잠깐 스핀한 뒤
 *     {@link java.util.concurrent.locks.LockSupport#park(Object)}로 잠든다.</li>
 * <li>해제할 때 {@code state}를 되돌리고, 큐의 다음 노드를 {@code unpark}로 깨운다.</li>
 * </ul>
 *
 * <h2>공통 공식: state CAS + 큐 + park</h2>
 * 이 패키지와 {@code java.util.concurrent}의 동기화 도구는 모두 같은 공식으로 만들어져 있다.
 * <b>"{@code state}를 CAS로 바꿔 보고, 실패하면 큐에 넣고 park한다. 해제할 때 큐에서 꺼내 unpark한다."</b>
 * 도구마다 다른 것은 {@code state}의 의미뿐이다.
 * <ul>
 * <li>{@code ReentrantLock}: 보유(재진입) 횟수. 0이면 비어 있다.</li>
 * <li>{@code ReentrantReadWriteLock}: 상위 16비트는 읽기 보유 수, 하위 16비트는 쓰기 재진입 수.</li>
 * <li>{@code Semaphore}: 남은 허가 수.</li>
 * <li>{@code CountDownLatch}: 남은 카운트. 0이 되면 기다리던 스레드가 모두 통과한다.</li>
 * </ul>
 * {@code StampedLock}은 AQS를 쓰지 않지만 같은 공식을 자체 {@code state}와 큐로 직접 구현했다.
 * {@code Condition}은 이 위에 조건 큐를 하나 더 얹은 것이다. 층으로 보면 이렇다.
 * <pre>{@code
 * ConditionWrapper         await/signal + buffer condition (while)
 *         |
 * ConditionObject (AQS)    condition queue + release/reacquire lock + wake timing
 *         |
 * AQS                      state CAS + sync queue
 *         |
 * LockSupport              park/unpark (one permit per thread)   <- LockSupportWrapper
 *         |
 * HotSpot Parker           futex (Linux) / WaitForSingleObject (Windows)
 * }</pre>
 *
 * <h2>OS 관점</h2>
 * {@code LockSupport.park} → {@code Unsafe.park} → HotSpot의 스레드별 {@code Parker} 객체 →
 * Linux {@code pthread_cond_wait}/{@code pthread_cond_timedwait}(futex), Windows {@code WaitForSingleObject}.
 * 모니터와 마찬가지로 결국 커널 대기다. 다만 대기 큐 관리는 JVM C++ 코드가 아니라 자바 코드(AQS)가 한다.
 * 그래서 대기 중인 스레드 상태가 {@code BLOCKED}가 아니라 {@code WAITING}/{@code TIMED_WAITING}이다.
 * 스레드 덤프에는 {@code parking to wait for <0x...> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)}로 나온다.
 *
 * <h2>모니터 대비 얻는 것</h2>
 * <ul>
 * <li>시간 제한 획득({@code tryLock(timeout)}), 인터럽트 가능한 획득({@code lockInterruptibly}).
 *     인터럽트로 빠져나올 수 있는 것은 <b>획득을 기다리는 동안</b>뿐이다. 이미 얻은 락은 인터럽트로 풀리지 않는다.</li>
 * <li>공정성 선택, 한 락에 조건 큐 여러 개({@code Condition})</li>
 * <li>읽기/쓰기 분리({@code ReadWriteLock}), 낙관적 읽기({@code StampedLock})</li>
 * <li>Java 21 가상 스레드에서 park하면 캐리어 스레드를 놓아준다(pinning 없음).</li>
 * </ul>
 * 대가: {@code unlock()}을 반드시 {@code finally}에서 직접 불러야 한다.
 *
 * <h2>예제</h2>
 * 예제마다 API 하나를 감싼 래퍼이고, 이름은 감싼 API 이름 뒤에 {@code Wrapper}를 붙였다.
 * <ol>
 * <li>{@link com.gunha.lock.locks.ReentrantLockWrapper}: {@code ReentrantLock}의 재진입·tryLock·인터럽트·공정성</li>
 * <li>{@link com.gunha.lock.locks.ConditionWrapper}: {@code Condition} 두 개로 만든 생산자-소비자</li>
 * <li>{@link com.gunha.lock.locks.ReentrantReadWriteLockWrapper}: {@code ReentrantReadWriteLock}의 공유 읽기·배타 쓰기·다운그레이드</li>
 * <li>{@link com.gunha.lock.locks.StampedLockWrapper}: {@code StampedLock}의 낙관적 읽기</li>
 * <li>{@link com.gunha.lock.locks.LockSupportWrapper}: 모든 락의 바닥에 있는 {@code LockSupport.park/unpark}</li>
 * </ol>
 */
package com.gunha.lock.locks;
