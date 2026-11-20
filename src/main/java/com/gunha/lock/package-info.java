/**
 * 자바 락 학습 예제. 하위 패키지를 번호 순서대로 읽는다.
 * <ol>
 * <li>{@link com.gunha.lock.monitor}: JVM 내장 모니터({@code synchronized}, {@code wait/notify})</li>
 * <li>{@link com.gunha.lock.locks}: 자바 코드로 만든 명시적 락(AQS, {@code ReentrantLock}, {@code Condition})</li>
 * <li>{@link com.gunha.lock.concurrent}: 동기화 도구와 원자 변수</li>
 * </ol>
 * 용어(happens-before, 캐시 라인, futex 등)는
 * <a href="https://github.com/gunhaa/java_lock/blob/main/docs/README.md">선행 지식 문서</a>에 있다.
 *
 * <h2 id="four-answers">해법은 네 가지다</h2>
 * 여러 스레드가 하나를 동시에 건드릴 때 할 수 있는 선택은 많지 않다. 이 프로젝트의 예제는 모두 아래 넷 중 하나이거나 그 조합이다.
 * <ul>
 * <li><b>한 번에 하나만</b>: 락. {@code synchronized}, {@code ReentrantLock}</li>
 * <li><b>일단 하고 나중에 확인</b>: CAS 재시도, 낙관적 읽기. {@code AtomicInteger}, {@code StampedLock}</li>
 * <li><b>기다리게 하고 나중에 깨움</b>: 큐 + park. 모니터의 {@code _cxq}/{@code _WaitSet}, AQS의 sync queue/condition queue</li>
 * <li><b>나눠서 부딪히지 않게</b>: 경합 분산. {@code LongAdder}, 읽기 공유({@code ReentrantReadWriteLock})</li>
 * </ul>
 *
 * <h2 id="same-patterns">같은 패턴이 다른 곳에서</h2>
 * 문제가 같으므로 해법도 층마다 이름만 바꿔 반복된다. 여기서 배운 구조는 DB, 메시지 큐, 분산 시스템을 볼 때도 그대로 쓰인다.
 * <table class="striped">
 * <caption>이 프로젝트의 개념과 다른 곳의 같은 구조</caption>
 * <thead>
 * <tr><th scope="col">이 프로젝트</th><th scope="col">다른 곳</th></tr>
 * </thead>
 * <tbody>
 * <tr><th scope="row">경합이 없으면 CAS 한 번, 있을 때만 잠듦 (모니터, AQS)</th><td>Linux futex, Go {@code sync.Mutex}(고루틴을 런타임이 재움)</td></tr>
 * <tr><th scope="row">락 대기 큐와 조건 대기 큐 ({@code _cxq}/{@code _WaitSet}, sync queue/condition queue)</th><td>OS 스케줄러의 대기 큐, pthread {@code cond_wait}, DB 락 대기</td></tr>
 * <tr><th scope="row">MPSC: 생산자들은 lock-free로 넣고 소비자 하나가 원자 연산 없이 꺼냄 ({@code _cxq})</th>
 *     <td>Netty 이벤트 루프의 작업 큐, Akka 메일박스. 원리는 같고 자료구조가 다르다. 작업은 순서가 중요해서 FIFO 큐를 쓰고,
 *     순서를 보장하지 않는 모니터는 가장 단순한 스택을 쓴다</td></tr>
 * <tr><th scope="row">낙관적 읽기 + {@code validate} ({@code StampedLock})</th><td>DB 낙관적 락(JPA {@code @Version}), HTTP {@code ETag}/{@code If-Match}</td></tr>
 * <tr><th scope="row">경합 분산 ({@code LongAdder})</th><td>{@code ConcurrentHashMap}의 버킷별 락, DB 샤딩, 분산 카운터</td></tr>
 * <tr><th scope="row">공정 vs 끼어들기(barging)</th><td>DB 트랜잭션 스케줄링, 네트워크 큐잉</td></tr>
 * <tr><th scope="row">신호 저장 여부 ({@code notify}는 사라지고 {@code unpark}는 permit으로 남음)</th><td>epoll의 엣지 트리거 vs 레벨 트리거</td></tr>
 * <tr><th scope="row">happens-before</th><td>DB 격리 수준, 분산 시스템의 Lamport 시계</td></tr>
 * </tbody>
 * </table>
 * 층이 올라갈수록 달라지는 것은 비용의 단위다. CAS 실패는 수십 ns, park는 수 μs, DB 락 대기는 ms, 분산 락은 네트워크 왕복이다.
 * 그래서 어느 층에서든 원칙은 같다. 경합을 줄인다(<a href="https://github.com/gunhaa/java_lock/blob/main/docs/03-hardware-and-os.md#lock-cost">docs/03 3.8절</a>).
 */
package com.gunha.lock;
