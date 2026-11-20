/**
 * [1] 모니터 락: JVM에 내장된 락. ({@code synchronized}, {@code Object.wait/notify})
 *
 * <h2>무엇인가</h2>
 * 모든 자바 객체는 락으로 쓸 수 있다. 락 상태는 객체 헤더의 mark word에 기록되고,
 * 경합이 생기거나 {@code wait()}을 부르면 HotSpot이 {@code ObjectMonitor}를 만들어 붙인다(팽창, inflation).
 * 락 획득·해제·대기를 전부 JVM이 처리하므로 라이브러리 코드가 아니라 바이트코드 명령과 VM 런타임의 영역이다.
 *
 * <h2 id="terms">용어</h2>
 * mark word와 {@code ObjectMonitor}는 자바 API로 보이지 않는 HotSpot 내부(C++ 코드)다. 그래서 이 설명은 HotSpot 소스
 * ({@code objectMonitor.hpp})의 이름을 그대로 쓴다. 예: {@code _owner}, {@code _recursions}, {@code _cxq}, {@code _EntryList}, {@code _WaitSet}.
 * 자바 명세에 나오는 이름은 wait set(JLS 17.2) 하나뿐이고, {@code _WaitSet}이 그것을 구현한다.
 * AQS 쪽 이름(sync queue, condition queue)과의 대응은 {@linkplain com.gunha.lock.locks##monitor-vs-aqs locks 패키지의 비교표}에 모았다.
 *
 * <h2>JVM 관점</h2>
 * <ul>
 * <li>블록: {@code monitorenter}/{@code monitorexit} 바이트코드. 메서드: {@code ACC_SYNCHRONIZED} 플래그.</li>
 * <li>경합이 없으면 CAS 한 번으로 끝난다(OS 호출 없음). 경합이 생기면 잠깐 스핀하고, 그래도 안 되면 OS에 재워 달라고 한다.</li>
 * <li>{@code _cxq}/{@code _EntryList}에서 진입을 기다리면 {@code BLOCKED}, {@code wait()}로 {@code _WaitSet}에서 기다리면 {@code WAITING}/{@code TIMED_WAITING}.</li>
 * <li>해제 → 같은 모니터의 이후 획득 사이에 happens-before가 성립한다(JLS 17.4.5). 가시성까지 함께 보장된다.</li>
 * </ul>
 *
 * <h2 id="object-header">객체 헤더 (64비트 HotSpot, JDK 21 기본 설정)</h2>
 * 압축 포인터를 쓰는 기본 설정(힙 32GB 미만)에서 {@link com.gunha.lock.monitor.SynchronizedWrapper} 인스턴스는 이렇게 놓인다.
 * <pre>{@code
 * offset  0                         8             12            16
 *         +-------------------------+-------------+-------------+
 *         |        mark word        |  klass ptr  |  int count  |
 *         |        (8 bytes)        |  (4 bytes)  |  (4 bytes)  |
 *         +-------------------------+-------------+-------------+
 *         |<------- object header (12 bytes) ---->|<-- field -->|
 * }</pre>
 * <ul>
 * <li><b>mark word</b>: 락 상태, identity hash, GC 나이처럼 실행 중에 바뀌는 정보. 락이 쓰는 곳이 여기다.</li>
 * <li><b>klass pointer</b>: 클래스 메타데이터({@code Klass})를 가리킨다. 압축 클래스 포인터라서 4바이트다.</li>
 * <li>필드는 헤더 바로 뒤에 온다. 객체는 8바이트 단위로 정렬되므로 {@code int} 하나가 남는 4바이트에 들어가 전체 16바이트가 된다.
 *     배열은 헤더 뒤에 {@code length}(4바이트)가 더 붙는다.</li>
 * </ul>
 *
 * <h3>mark word 비트 배치 (잠기지 않은 상태)</h3>
 * <pre>{@code
 * +--------------+--------------------+-----+-------+-----+------+
 * |    unused    |   identity hash    | gap |  age  | gap | lock |
 * |   [63..39]   |      [38..8]       | [7] | [6..3]| [2] |[1..0]|
 * |    25 bit    |       31 bit       |  1  |   4   |  1  |  2   |
 * +--------------+--------------------+-----+-------+-----+------+
 * }</pre>
 * <ul>
 * <li><b>identity hash</b>: {@code System.identityHashCode()}(재정의하지 않은 {@code hashCode()}) 값.
 *     처음 호출될 때 계산해 저장하므로 그 전까지는 0이다.</li>
 * <li><b>age</b>: Young GC에서 살아남은 횟수. 4비트라 최대 15이고, {@code -XX:MaxTenuringThreshold}의 최댓값이 15인 이유다.</li>
 * <li><b>[2]</b>: JDK 17까지는 편향 락 비트였다. 편향 락이 제거된 뒤 빈자리로 남았다.</li>
 * </ul>
 *
 * <h3>하위 2비트에 따른 해석</h3>
 * {@code lock} 비트가 나머지 62비트를 어떻게 읽을지 정한다. 락이 걸리면 hash와 age 자리를 포인터가 덮어쓰고,
 * 원래 값은 포인터가 가리키는 곳으로 옮겨 보관한다.
 * <pre>{@code
 * unlocked  [ unused:25 | hash:31 | -:1 | age:4 | -:1 | 01 ]
 * stack     [ ptr to Lock Record on owner's stack:62  | 00 ]
 * inflated  [ ptr to ObjectMonitor:62                 | 10 ]
 * GC mark   [ forwarding address:62                   | 11 ]
 * }</pre>
 * <ol>
 * <li><b>잠기지 않음</b> ({@code 01}): 위 비트 배치 그대로.</li>
 * <li><b>스택 락 / thin lock</b> ({@code 00}): 경합이 없을 때. 소유 스레드가 자기 스택에 Lock Record를 만들고
 *     원래 mark word를 거기 복사한 뒤(displaced mark), CAS({@code lock cmpxchg}) 한 번으로 mark word가 Lock Record를 가리키게 한다.
 *     스택 주소는 8바이트 정렬이라 하위 2비트가 원래 {@code 00}이다. OS는 관여하지 않는다.
 * <pre>{@code
 * Heap                                   T1's stack frame
 * +--------------------+                 +---------------------+
 * | mark  [ptr  | 00]  |---------------->| Lock Record         |
 * | klass              |                 |   displaced mark    |
 * | count              |                 |   obj ref           |
 * +--------------------+                 +---------------------+
 * }</pre></li>
 * <li><b>팽창 / inflated</b> ({@code 10}): 다른 스레드가 같은 락을 원하거나 {@code wait()}을 부르면
 *     {@code ObjectMonitor}를 만들어 mark word가 그것을 가리키게 한다. {@code ObjectMonitor}는 자바 힙이 아니라 JVM의 네이티브 메모리에 있다.
 * <pre>{@code
 * Java heap                              native memory (outside the Java heap)
 * +--------------------+                 +---------------------+
 * | mark  [ptr  | 10]  |---------------->| ObjectMonitor       |
 * | klass              |                 |   _header           |
 * | count              |                 |   _owner            |
 * +--------------------+                 |   _recursions       |
 *                                        |   _cxq, _EntryList  |
 *                                        |   _WaitSet          |
 *                                        +---------------------+
 * }</pre>
 *     {@code _header}는 원래 mark word다. 나머지 필드의 뜻과 스레드가 그 사이를 오가는 규칙은 아래 {@linkplain com.gunha.lock.monitor##object-monitor ObjectMonitor의 기본 구성} 절에서 설명한다.</li>
 * <li><b>GC 표시</b> ({@code 11}): GC가 객체를 옮길 때 새 주소(forwarding pointer)를 기록한다. 락과는 관계없다.</li>
 * </ol>
 * hash와 락은 같은 자리를 다툰다. 스택 락이 걸린 객체에 처음 {@code identityHashCode()}를 부르면 hash를 둘 곳이 없어서,
 * 경합이 없어도 {@code ObjectMonitor}로 팽창시킨 뒤 {@code _header}에 저장한다.
 *
 * <h3 id="object-monitor">ObjectMonitor의 기본 구성 (JDK 21)</h3>
 * 소유자 필드 하나({@code _owner})와 스레드 리스트 셋({@code _cxq}, {@code _EntryList}, {@code _WaitSet})이다.
 * 리스트 셋은 하는 일로 보면 두 종류다. {@code _cxq}와 {@code _EntryList}는 <b>락</b>을 기다리는 스레드를, {@code _WaitSet}은 <b>{@code wait()}</b>으로 조건을 기다리는 스레드를 담는다.
 * {@code synchronized}, {@code wait()}, {@code notify()}는 결국 스레드를 이 필드들 사이에서 옮기는 일이다.
 * 팽창 전(스택 락)에는 {@code ObjectMonitor}가 없으므로 이 필드들도 없다.
 * <pre>{@code
 *             synchronized                         monitorexit
 *                   |                                   ^
 *                   v                                   |
 *         +-------------------+  acquire  +-------------------+
 *         | _cxq / _EntryList |---------->|      _owner       |
 *         | BLOCKED           |           | running (1 max)   |
 *         | T3, T4            |           | T1, _recursions   |
 *         +-------------------+           +-------------------+
 *                   ^  notify(): one                |  wait(): release monitor
 *                   |  notifyAll(): all             v
 *                   |                     +-------------------+
 *                   +---------------------|     _WaitSet      |
 *                                         | WAITING           |
 *                                         | T5, T6            |
 *                                         +-------------------+
 * }</pre>
 * <ul>
 * <li><b>{@code _owner}</b>: 모니터를 가진 스레드. 리스트가 아니라 스레드 하나를 가리키는 필드라 항상 최대 하나이고, 재진입하면 {@code _recursions}만 는다.</li>
 * <li><b>{@code _cxq}, {@code _EntryList}</b>: 모니터에 들어가려고 기다리는 스레드({@code BLOCKED}).
 *     {@code synchronized}에 스레드가 몰리면 경합에서 진 스레드는 <b>전부 여기로</b> 간다. 둘의 차이는 아래 {@linkplain com.gunha.lock.monitor##cxq-entrylist _cxq와 _EntryList} 절에 있다.</li>
 * <li><b>{@code _WaitSet}</b>: {@code wait()}을 부른 스레드({@code WAITING}). 모니터를 <b>완전히</b> 놓고(재진입 횟수는 저장) 여기서 잠든다.
 *     {@code _cxq}/{@code _EntryList}와 달리, 모니터가 비어도 스스로 나오지 못하고 누군가 notify해야 나온다.
 *     락 경합만으로는 아무도 여기 들어오지 않는다. 들어오는 길은 소유자가 직접 부르는 {@code wait()} 하나뿐이다.</li>
 * </ul>
 * 세 리스트 어디서 잠들든 {@link java.util.concurrent.locks.LockSupport#park()}와 같은 <b>커널 대기</b>다.
 * 스레드가 CPU를 쓰지 않고 OS 스케줄러의 실행 대기열에서 빠진다. 거쳐 가는 HotSpot 객체만 다르다.
 * <pre>{@code
 * Object.wait() / monitor entry -> ParkEvent (per thread) --+
 *                                                           +--> Linux:   pthread_cond_wait -> futex
 * LockSupport.park() (AQS)      -> Parker    (per thread) --+     Windows: WaitForSingleObject (event)
 * }</pre>
 * 그래서 {@code wait()}로 잠든 스레드와 {@code park()}로 잠든 스레드는 OS가 보기에 같은 상태이고, 깨어날 때 컨텍스트 스위치 비용도 같다
 * (OS 쪽 자세한 동작은 <a href="https://github.com/gunhaa/java_lock/blob/main/docs/03-hardware-and-os.md#futex">docs/03 3.7절</a>).
 * 자바 스레드 상태는 대기를 누가 관리하느냐가 아니라 <b>무엇을 기다리느냐</b>로 정해진다({@link java.lang.Thread.State} 명세).
 * 모니터에 들어가려고(또는 {@code wait()} 뒤 다시 들어가려고) {@code _cxq}/{@code _EntryList}에서 기다리면 {@code BLOCKED}이고,
 * {@code wait()}로 {@code _WaitSet}에서 기다리면 {@code park()}와 똑같이 {@code WAITING}이다.
 * 이동 규칙은 다음과 같다.
 * <ol>
 * <li><b>진입</b>: {@code _owner}가 비어 있으면 CAS로 바로 차지한다. 아니면 적응형 스핀(adaptive spinning)으로 잠깐 버틴 뒤 {@code _cxq}에 들어가 잠든다.</li>
 * <li><b>해제</b>: {@code _recursions}가 0이 되면 {@code _owner}를 비우고 기다리던 스레드 하나를 깨운다.
 *     누구를 깨우는지와 순서가 보장되지 않는 이유는 아래 {@linkplain com.gunha.lock.monitor##cxq-entrylist _cxq와 _EntryList} 절에 있다(공정 모드 없음).</li>
 * <li><b>{@code wait()}</b>: {@code _owner} → {@code _WaitSet}. 모니터가 비므로 {@code _cxq}/{@code _EntryList}의 다른 스레드가 들어올 수 있다.</li>
 * <li><b>{@code notify()}/{@code notifyAll()}</b>: {@code _WaitSet}에서 하나/전부를 <b>{@code _cxq}/{@code _EntryList}로 옮길 뿐</b>이다. 바로 실행되지 않는다.
 *     notify한 스레드가 모니터를 놓은 뒤, 옮겨진 스레드들은 원래 {@code _cxq}/{@code _EntryList}에 있던 스레드·새로 온 스레드와 함께 <b>순서 보장 없이 하나씩</b> 모니터를 얻고,
 *     그때 {@code wait()}에서 돌아온다. 그래서 돌아온 뒤에는 조건을 {@code while}로 다시 확인해야 한다.</li>
 * </ol>
 * HotSpot은 notify 시점에 OS 수준에서 깨우지 않는다. 어차피 notify한 스레드가 모니터를 쥐고 있으므로, 해제할 때 깨워 컨텍스트 스위치를 아낀다.
 *
 * <h3 id="cxq-entrylist">{@code _cxq}와 {@code _EntryList} (JDK 21)</h3>
 * 둘 다 락 CAS에서 진 스레드가 {@code BLOCKED}로 기다리는 곳이고, 역할만 다르다.
 * <ul>
 * <li><b>{@code _cxq}</b>: 새로 온 스레드가 들어가는 입구. 여러 스레드가 동시에 넣으므로 락 없이 CAS로 머리에 붙인다.
 *     이름과 달리 스택이라 늦게 온 스레드가 앞에 선다
 *     (<a href="https://github.com/gunhaa/java_lock/blob/main/docs/03-hardware-and-os.md#lock-free-stack">docs/03 lock-free 스택</a>).</li>
 * <li><b>{@code _EntryList}</b>: 락을 놓는 소유자만 다루는 다음 후보 목록. 비면 {@code _cxq}를 통째로 떼어 와 채운다.</li>
 * </ul>
 * <b>규칙은 정해져 있다</b>: 해제할 때 {@code _EntryList} 맨 앞 하나를 깨운다. 그래서 {@code _EntryList}가 먼저, {@code _cxq}가 그다음이다.
 * {@code notify()}로 {@code _WaitSet}에서 나온 스레드도 둘 중 한 곳에 들어가 똑같이 기다린다.
 * <p>
 * <b>하지만 순서는 보장되지 않는다</b>: 깨어난 스레드도 락 CAS를 다시 해야 하고, 그사이 막 도착한 스레드가 이길 수 있다(barging).
 * 오래 기다린 스레드가 계속 밀릴 수도 있다. 순서가 필요하면 공정 모드 {@code ReentrantLock}({@code new ReentrantLock(true)})을 쓴다.
 *
 * <h3>JDK 버전별 변화</h3>
 * <ul>
 * <li><b>JDK 14까지</b>: 편향 락(biased locking)이 기본으로 켜져 있었다. 처음 잠근 스레드의 포인터(아래 {@code thread:54})를 mark word에 새겨 두고,
 *     같은 스레드가 다시 잠글 때는 CAS도 하지 않는다. 다른 스레드가 오면 safepoint에서 편향을 취소(revoke)해야 해서 비용이 컸다.
 * <pre>{@code
 * unlocked  [ unused:25 | hash:31 | -:1 | age:4 | b=0 | 01 ]
 * biased    [ thread:54 | epoch:2 | -:1 | age:4 | b=1 | 01 ]
 * }</pre></li>
 * <li><b>JDK 15</b>: 편향 락 기본 비활성화·deprecated(JEP 374). {@code Vector}·{@code Hashtable}처럼 메서드마다 동기화하는 옛 컬렉션을 요즘은 덜 써서 편향 락의 이득이 줄었고, 취소 비용과 VM 코드 복잡도가 이득보다 크다고 판단했다.</li>
 * <li><b>JDK 18</b>: 편향 락 코드 제거. {@code b} 비트가 빈자리(위 배치의 [2])가 되었다.</li>
 * <li><b>JDK 21</b>: {@code -XX:LockingMode} 추가. 기본값 1(LM_LEGACY)이 위의 스택 락 방식이고, 2(LM_LIGHTWEIGHT)는 옵션이다.</li>
 * <li><b>JDK 23</b>: LM_LIGHTWEIGHT가 기본값. {@code 00} 상태에서도 hash와 age가 mark word에 그대로 남고,
 *     소유자는 Lock Record 포인터 대신 스레드 안의 작은 배열(lock stack)에 기록된다. mark word를 밖으로 옮기지 않으므로 hash 때문에 팽창하는 일이 줄어든다.</li>
 * <li><b>JDK 24</b>: {@code synchronized} 안에서 블로킹해도 가상 스레드가 캐리어를 붙잡지 않는다(JEP 491).
 *     Compact Object Headers(JEP 450)가 실험 기능으로 들어왔다. klass pointer를 mark word 안에 접어 넣어 헤더 전체가 8바이트가 된다.
 * <pre>{@code
 * compact   [ klass:22 | hash:31 | -:4 | age:4 | f:1 | lock:2 ]
 * }</pre>
 *     {@code f}는 GC가 쓰는 self-forwarded 비트이고, {@code -:4}는 Valhalla(값 객체)용으로 비워 둔 자리다.</li>
 * <li><b>JDK 25</b>: Compact Object Headers가 정식 기능이 되었다(JEP 519). 기본값은 꺼져 있고 {@code -XX:+UseCompactObjectHeaders}로 켠다.
 *     {@code ObjectMonitor}의 {@code _cxq}와 {@code _EntryList}가 {@code _entry_list} 하나로 합쳐졌다(JDK-8343840).
 *     락을 기다리는 리스트와 {@code _WaitSet}이 따로라는 구조와 barging은 그대로다.</li>
 * </ul>
 *
 * <h2>ReentrantLock과의 관계</h2>
 * {@code ReentrantLock}은 이 구조(소유자, 락을 기다리는 리스트, 조건을 기다리는 리스트, park)를 자바 코드(AQS)로 다시 만들고,
 * 대기를 포기하거나 순서를 정하는 등 호출자가 제어할 수단을 더한 것이다. 필드 대응과 차이는 {@linkplain com.gunha.lock.locks##monitor-vs-aqs locks 패키지의 비교표}에 있다.
 *
 * <h2>예제</h2>
 * 래퍼 두 개는 감싼 언어 기능({@code synchronized}, {@code Object.wait/notify}) 이름 뒤에 {@code Wrapper}를 붙였다.
 * {@code UnsafeCounter}는 감싸는 API가 없는 반례라서 래퍼가 아니다.
 * <ol>
 * <li>{@link com.gunha.lock.monitor.UnsafeCounter}: 락이 없을 때 생기는 갱신 유실(volatile로도 못 막음)</li>
 * <li>{@link com.gunha.lock.monitor.SynchronizedWrapper}: {@code synchronized}의 상호 배제, 재진입, BLOCKED 상태</li>
 * <li>{@link com.gunha.lock.monitor.WaitNotifyWrapper}: {@code wait/notifyAll}로 만든 생산자-소비자</li>
 * </ol>
 */
package com.gunha.lock.monitor;
