/**
 * [2] 명시적 락: {@code java.util.concurrent.locks} 패키지.
 *
 * <h2>무엇인가</h2>
 * 모니터와 달리 JVM 명령이 아니라 <b>자바 코드로 구현된 라이브러리</b>다(Doug Lea, JSR 166, Java 5).
 * 핵심은 거의 모든 구현이 공유하는 뼈대인 {@link java.util.concurrent.locks.AbstractQueuedSynchronizer}(AQS)다.
 *
 * <h2 id="aqs">AQS 구조 (JVM 관점)</h2>
 * <ul>
 * <li>{@code volatile int state}: 락 상태. 의미는 구현이 정한다(아래 {@linkplain com.gunha.lock.locks##formula 공통 공식} 절의 목록).</li>
 * <li>획득 시도 = {@code state}에 대한 CAS. JIT이 x86 {@code lock cmpxchg}, ARM64 {@code casal}(또는 LL/SC) 명령으로 바꾼다.
 *     경합이 없으면 이 한 명령으로 끝난다.</li>
 * <li>실패하면 <b>sync queue</b> 꼬리({@code tail})에 CAS로 노드를 붙인다(락 없이 넣는 lock-free 삽입). sync queue는 AQS가 {@code head}/{@code tail} 필드로 관리하는,
 *     락을 기다리는 스레드 줄(CLH 큐를 변형한 이중 연결 리스트)이다. 이름의 sync는 AQS를 상속한 각 락의 내부 클래스
 *     {@code Sync}(동기화기)에서 왔고, JDK 문서는 같은 큐를 wait queue, CLH queue라고도 부른다.
 *     이 프로젝트는 {@code Condition}의 condition queue와 구분하려고 sync queue로 통일했다.
 *     큐에 들어가는 동안 획득을 몇 번 다시 시도해 보고, 그래도 안 되면 {@link java.util.concurrent.locks.LockSupport#park(Object)}로 잠든다.
 *     모니터의 적응형 스핀처럼 일부러 오래 도는 단계는 없다.</li>
 * <li>해제할 때 {@code state}를 되돌리고, 큐의 다음 노드를 {@code unpark}로 깨운다.</li>
 * </ul>
 *
 * <h2 id="formula">공통 공식: state CAS + 큐 + park</h2>
 * 이 패키지와 {@code java.util.concurrent}의 AQS 기반 동기화 도구는 같은 공식으로 만들어져 있다.
 * <b>"{@code state}를 CAS로 바꿔 보고, 실패하면 큐에 넣고 park한다. 해제할 때 큐에서 꺼내 unpark한다."</b>
 * 도구마다 다른 것은 {@code state}의 의미뿐이다.
 * <ul>
 * <li>{@code ReentrantLock}: 보유(재진입) 횟수. 0이면 비어 있다.</li>
 * <li>{@code ReentrantReadWriteLock}: 상위 16비트는 읽기 보유 수, 하위 16비트는 쓰기 재진입 수.</li>
 * <li>{@code Semaphore}: 남은 허가 수.</li>
 * <li>{@code CountDownLatch}: 남은 카운트. 0이 되면 기다리던 스레드가 모두 통과한다.</li>
 * </ul>
 * {@code StampedLock}은 AQS를 쓰지 않지만 같은 공식을 자체 {@code state}와 큐로 직접 구현했다.
 * 공식에서 벗어나는 도구({@code CyclicBarrier}, 원자 변수)는 {@link com.gunha.lock.concurrent} 패키지 설명에 정리했다.
 * {@code Condition}은 이 위에 condition queue를 하나씩({@code newCondition()}마다) 얹은 것이다. 층으로 보면 이렇다.
 * <pre>{@code
 * ConditionWrapper         await/signal + buffer condition (while)
 *         |
 * ConditionObject (AQS)    condition queue + release/reacquire lock + wake timing
 *         |
 * AQS                      state CAS + sync queue                <- QueuedSynchronizer (mini AQS)
 *         |
 * LockSupport              park/unpark (one permit per thread)
 *         |
 * HotSpot Parker           futex (Linux) / WaitForSingleObject (Windows)
 * }</pre>
 *
 * <h2>OS 관점</h2>
 * 위 그림의 맨 아래 두 층이다. 모니터와 마찬가지로 결국 커널 대기다
 * (경로는 {@link com.gunha.lock.locks.QueuedSynchronizer}, OS 쪽 동작은 <a href="https://github.com/gunhaa/java_lock/blob/main/docs/03-hardware-and-os.md#futex">docs/03 3.7절</a>).
 * 스레드 상태는 {@code BLOCKED}가 아니라 {@code WAITING}/{@code TIMED_WAITING}이다. {@link java.lang.Thread.State} 명세상
 * {@code BLOCKED}는 모니터 진입 대기 전용이고, {@code park()}로 기다리는 스레드는 {@code WAITING}이기 때문이다.
 * 스레드 덤프에는 {@code parking to wait for <0x...> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)}로 나온다.
 *
 * <h2 id="monitor-vs-aqs">synchronized와 ReentrantLock: 같은 구조, 다른 제어</h2>
 * 두 락은 구조가 같다. 소유자 하나, 락을 기다리는 리스트, 조건을 기다리는 리스트가 있고, 리스트에 들어간 스레드는 OS 대기로 잠든다.
 * 다른 것은 그 구조를 <b>JVM 안에 감춰 두느냐(모니터), 자바 코드로 드러내 호출자가 제어하게 하느냐(AQS)</b>다.
 * <p>
 * 두 쪽 이름의 대응은 이 표에 모았다. 모니터 쪽은 HotSpot 소스(C++)의 필드 이름이고({@linkplain com.gunha.lock.monitor##terms monitor 패키지의 용어 절}),
 * AQS 쪽은 자바 필드·클래스 이름이다.
 * <table class="striped">
 * <caption>같은 구조</caption>
 * <thead>
 * <tr><th scope="col">자리</th><th scope="col">{@code synchronized} (HotSpot {@code ObjectMonitor})</th><th scope="col">{@code ReentrantLock} (AQS)</th></tr>
 * </thead>
 * <tbody>
 * <tr><th scope="row">소유자</th><td>{@code _owner}, {@code _recursions}</td><td>{@code exclusiveOwnerThread}, {@code state}</td></tr>
 * <tr><th scope="row">락을 기다리는 리스트</th><td>{@code _cxq}, {@code _EntryList}</td><td>sync queue({@code head}/{@code tail})</td></tr>
 * <tr><th scope="row">조건을 기다리는 리스트</th><td>{@code _WaitSet} (객체당 하나)</td><td>condition queue ({@code Condition}마다 하나)</td></tr>
 * <tr><th scope="row">조건 대기에서 깨우기</th><td>{@code notify()}: {@code _WaitSet} → {@code _cxq}/{@code _EntryList}로 옮김</td>
 *     <td>{@code signal()}: condition queue → sync queue로 옮김</td></tr>
 * <tr><th scope="row">재우기·깨우기</th><td>{@code ParkEvent}</td><td>{@code LockSupport.park/unpark} ({@code Parker})</td></tr>
 * <tr><th scope="row">해제</th><td colspan="2">소유권을 넘겨주지 않는다. 대기자 하나를 깨울 뿐이고, 깨어난 스레드가 다시 CAS로 락을 얻는다.
 *     이때 새로 온 스레드가 끼어들 수 있다(barging). {@code ReentrantLock}의 공정 모드만 끼어들기를 막는다.</td></tr>
 * </tbody>
 * </table>
 * <table class="striped">
 * <caption>다른 것: 호출자가 제어할 수 있느냐</caption>
 * <thead>
 * <tr><th scope="col">제어</th><th scope="col">{@code synchronized}</th><th scope="col">{@code ReentrantLock}</th></tr>
 * </thead>
 * <tbody>
 * <tr><th scope="row">락 대기를 포기</th><td>불가. 인터럽트해도 계속 기다린다</td>
 *     <td>{@code tryLock(timeout)}, {@code lockInterruptibly()}. 포기할 수 있는 것은 <b>기다리는 동안</b>뿐이고, 얻은 락은 인터럽트로 풀리지 않는다</td></tr>
 * <tr><th scope="row">기다리지 않고 시도</th><td>불가</td><td>{@code tryLock()}</td></tr>
 * <tr><th scope="row">대기 순서</th><td>정할 수 없다</td><td>{@code new ReentrantLock(true)}로 FIFO</td></tr>
 * <tr><th scope="row">조건 대기 리스트 수</th><td>하나로 고정</td><td>{@code newCondition()}마다 하나</td></tr>
 * <tr><th scope="row">상태 조회</th><td>{@code Thread.holdsLock()}뿐</td><td>{@code isLocked()}, {@code getQueueLength()}, {@code hasWaiters()} 등</td></tr>
 * <tr><th scope="row">해제</th><td>블록이 끝나면 자동</td><td>{@code finally}에서 {@code unlock()}을 직접 불러야 한다(제어의 대가)</td></tr>
 * </tbody>
 * </table>
 * 구현이 달라서 생기는 차이도 있다. 이것은 호출자가 고를 수 없다.
 * <ul>
 * <li><b>스레드 상태</b>: 모니터에 들어가려고 기다리면 {@code BLOCKED}, AQS는 {@code park()}로 기다리므로 {@code WAITING}이다(위 OS 관점).</li>
 * <li><b>리스트 구조와 순서</b>: sync queue는 리스트 하나에 꼬리로 넣고 머리부터 깨우므로 도착 순서(FIFO)다. 비공정 모드는 새로 온 스레드의 끼어들기만 허용한다.
 *     모니터는 입구({@code _cxq})와 다음 후보 목록({@code _EntryList}) 둘로 나뉘고, 깨어나는 순서가 도착 순서와 다르다.</li>
 * <li><b>스핀</b>: 모니터는 잠들기 전에 적응형 스핀을 하고, AQS는 거의 바로 park한다.</li>
 * <li><b>Java 21 가상 스레드</b>: {@code synchronized} 안에서 블로킹하면 캐리어 스레드가 묶인다(pinning, JDK 24에서 해결). AQS의 park는 캐리어를 놓아준다.</li>
 * </ul>
 *
 * <h2 id="read-heavy">읽기가 많을 때: 비관적 공유와 낙관적 검증</h2>
 * {@code ReentrantReadWriteLock}과 {@code StampedLock}은 <b>같은 문제</b>(읽기가 많고 쓰기가 드물다)를 <b>반대 전략</b>으로 푼다.
 * 충돌을 미리 막느냐(비관), 일단 읽고 나중에 확인하느냐(낙관)의 차이다. 낙관적 읽기도 확인에 실패한 값은 버리므로 결과는 항상 맞다.
 * <table class="striped">
 * <caption>읽기 락과 낙관적 읽기</caption>
 * <thead>
 * <tr><th scope="col"></th><th scope="col">{@code ReentrantReadWriteLock} 읽기 락</th><th scope="col">{@code StampedLock} 낙관적 읽기</th></tr>
 * </thead>
 * <tbody>
 * <tr><th scope="row">전략</th><td>비관적 공유: 쓰기가 못 들어오게 막고 읽는다</td><td>낙관적 검증: 막지 않고 읽은 뒤 {@code validate}로 확인한다</td></tr>
 * <tr><th scope="row">읽을 때 {@code state}에 쓰나</th><td>쓴다. 얻을 때 +1, 놓을 때 -1 (CAS 두 번)</td><td>안 쓴다. 읽기만 한다</td></tr>
 * <tr><th scope="row">쓰기와 겹치면</th><td>쓰기가 끝날 때까지 기다린다</td><td>읽은 값을 버리고 다시 읽는다(보통 진짜 읽기 락으로)</td></tr>
 * <tr><th scope="row">유리할 때</th><td>읽기 구간이 길 때, 쓰기가 있어도 기다리면 될 때</td><td>읽기가 짧고 잦으며 쓰기가 드물 때</td></tr>
 * <tr><th scope="row">구현</th><td>AQS(공유 모드 + 배타 모드), 재진입, 쓰기 락의 {@code Condition}</td><td>AQS가 아닌 자체 구현. 재진입·소유자·{@code Condition} 없음</td></tr>
 * </tbody>
 * </table>
 * 비관적 읽기 락은 서로를 기다리게 하지는 않지만, 모든 독자가 같은 {@code state}에 CAS로 써서 그 캐시 라인을 코어 사이에서 계속 주고받는다.
 * 읽기 구간이 짧을수록 이 비용이 상대적으로 커지고, 낙관적 읽기는 이것을 없앤다.
 * 최상위 설명의 {@linkplain com.gunha.lock##four-answers 해법 네 가지}로 보면 읽기 락은 "나눠서 부딪히지 않게", 낙관적 읽기는 "일단 하고 나중에 확인"이다.
 *
 * <h2>예제</h2>
 * 예제마다 API 하나를 감싼 래퍼이고, 이름은 감싼 API 이름 뒤에 {@code Wrapper}를 붙였다.
 * {@code QueuedSynchronizer}는 {@code LockSupport}를 감싼 것이 아니라 그것으로 AQS를 작게 다시 만든 예제라서 래퍼가 아니다.
 * <ol>
 * <li>{@link com.gunha.lock.locks.ReentrantLockWrapper}: {@code ReentrantLock}의 재진입·tryLock·인터럽트·공정성</li>
 * <li>{@link com.gunha.lock.locks.ConditionWrapper}: {@code Condition} 두 개로 만든 생산자-소비자</li>
 * <li>{@link com.gunha.lock.locks.ReentrantReadWriteLockWrapper}: {@code ReentrantReadWriteLock}의 비관적 공유(읽기 공유·쓰기 배타)</li>
 * <li>{@link com.gunha.lock.locks.StampedLockWrapper}: {@code StampedLock}의 낙관적 검증(낙관적 읽기와 {@code validate})</li>
 * <li>{@link com.gunha.lock.locks.QueuedSynchronizer}: 모든 락의 바닥에 있는 {@code LockSupport.park/unpark}와 큐로 만든 미니 AQS</li>
 * </ol>
 */
package com.gunha.lock.locks;
