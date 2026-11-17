/**
 * 모니터 락: JVM에 내장된 락. ({@code synchronized}, {@code Object.wait/notify})
 *
 * <h2>무엇인가</h2>
 * 모든 자바 객체는 락으로 쓸 수 있다. 락 상태는 객체 헤더의 mark word에 기록되고,
 * 경합이 생기면 HotSpot이 C++ 객체인 {@code ObjectMonitor}를 붙인다(inflation).
 * 락 획득·해제·대기를 전부 JVM이 처리하므로 라이브러리 코드가 아니라 바이트코드 명령과 VM 런타임의 영역이다.
 *
 * <h2>JVM 관점</h2>
 * <ul>
 * <li>블록: {@code monitorenter}/{@code monitorexit} 바이트코드. 메서드: {@code ACC_SYNCHRONIZED} 플래그.</li>
 * <li>경합이 없으면 CAS 한 번으로 끝난다(OS 호출 없음). 경합이 생기면 잠깐 스핀하고, 그래도 안 되면 OS에 재워 달라고 한다.</li>
 * <li>락 진입 대기 = {@code BLOCKED}, {@code wait()} 대기 = {@code WAITING}/{@code TIMED_WAITING}.</li>
 * <li>해제 → 같은 모니터의 이후 획득 사이에 happens-before가 성립한다(JLS 17.4.5). 가시성까지 함께 보장된다.</li>
 * </ul>
 *
 * <h2>객체 헤더 (64비트 HotSpot, JDK 21 기본 설정)</h2>
 * 압축 포인터를 쓰는 기본 설정(힙 32GB 미만)에서 {@link com.gunha.lock.monitor.SynchronizedCounter} 인스턴스는 이렇게 놓인다.
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
 * Heap                                   C++ heap (native memory)
 * +--------------------+                 +---------------------+
 * | mark  [ptr  | 10]  |---------------->| ObjectMonitor       |
 * | klass              |                 |   _header           |
 * | count              |                 |   _owner            |
 * +--------------------+                 |   _recursions       |
 *                                        |   _cxq, _EntryList  |
 *                                        |   _WaitSet          |
 *                                        +---------------------+
 * }</pre>
 *     {@code _header}는 원래 mark word, {@code _owner}는 소유 스레드, {@code _recursions}는 재진입 횟수다.
 *     진입을 기다리는 스레드는 {@code _cxq}/{@code _EntryList}에서 {@code BLOCKED}, {@code wait()}한 스레드는 {@code _WaitSet}에서 {@code WAITING}이 된다.
 *     진입 대기자는 먼저 적응형 스핀(adaptive spinning)으로 잠깐 버티고, 그래도 못 잡으면 OS 대기로 들어간다.</li>
 * <li><b>GC 표시</b> ({@code 11}): GC가 객체를 옮길 때 새 주소(forwarding pointer)를 기록한다. 락과는 관계없다.</li>
 * </ol>
 *
 * <h3>ObjectMonitor 안에서 스레드가 오가는 길</h3>
 * 모든 객체의 모니터는 같은 세 자리로 동시성을 제어한다. {@code synchronized}, {@code wait()}, {@code notify()}는
 * 결국 스레드를 이 세 자리 사이에서 옮기는 일이다.
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
 * <li><b>{@code _owner}</b>: 모니터를 가진 스레드. 항상 최대 하나이고, 재진입하면 {@code _recursions}만 는다.</li>
 * <li><b>{@code _cxq}/{@code _EntryList}</b>: 들어가려고 기다리는 스레드({@code BLOCKED}). 새로 온 스레드는 먼저 {@code _cxq}에 쌓이고,
 *     해제하는 스레드가 이를 {@code _EntryList}로 옮겨 다음 차례를 고른다. 둘 다 "입장 대기줄"이다.</li>
 * <li><b>{@code _WaitSet}</b>: {@code wait()}을 부른 스레드({@code WAITING}). 모니터를 <b>완전히</b> 놓고(재진입 횟수는 저장) 여기서 잠든다.
 *     입장 대기줄과 달리, 모니터가 비어도 스스로 나오지 못하고 누군가 notify해야 나온다.</li>
 * </ul>
 * 이동 규칙은 다음과 같다.
 * <ol>
 * <li><b>진입</b>: {@code _owner}가 비어 있으면 CAS로 바로 차지한다. 아니면 잠깐 스핀한 뒤 입장 대기줄에 들어가 잠든다.</li>
 * <li><b>해제</b>: {@code _recursions}가 0이 되면 {@code _owner}를 비우고 입장 대기줄의 스레드 하나를 깨운다.
 *     깨어난 스레드는 막 도착한 스레드와 다시 경쟁하므로 먼저 기다린 순서대로 들어간다는 보장이 없다(공정 모드 없음).</li>
 * <li><b>{@code wait()}</b>: {@code _owner} → {@code _WaitSet}. 모니터가 비므로 입장 대기줄의 다른 스레드가 들어올 수 있다.</li>
 * <li><b>{@code notify()}/{@code notifyAll()}</b>: {@code _WaitSet}에서 하나/전부를 <b>입장 대기줄로 옮길 뿐</b>이다. 바로 실행되지 않는다.
 *     notify한 스레드가 모니터를 놓은 뒤, 옮겨진 스레드들은 원래 줄에 있던 스레드·새로 온 스레드와 함께 <b>순서 보장 없이 하나씩</b> 모니터를 얻고,
 *     그때 {@code wait()}에서 돌아온다. 그래서 돌아온 뒤에는 조건을 {@code while}로 다시 확인해야 한다.</li>
 * </ol>
 * HotSpot은 notify 시점에 OS 수준에서 깨우지 않는다. 어차피 notify한 스레드가 모니터를 쥐고 있으므로, 해제할 때 깨워 컨텍스트 스위치를 아낀다.
 * <p>
 * hash와 락은 같은 자리를 다툰다. 스택 락이 걸린 객체에 처음 {@code identityHashCode()}를 부르면 hash를 둘 곳이 없어서,
 * 경합이 없어도 {@code ObjectMonitor}로 팽창시킨 뒤 {@code _header}에 저장한다.
 *
 * <h3>JDK 버전별 변화</h3>
 * <ul>
 * <li><b>JDK 14까지</b>: 편향 락(biased locking)이 기본으로 켜져 있었다. 처음 잠근 스레드의 ID를 mark word에 새겨 두고,
 *     같은 스레드가 다시 잠글 때는 CAS도 하지 않는다. 다른 스레드가 오면 safepoint에서 편향을 취소(revoke)해야 해서 비용이 컸다.
 * <pre>{@code
 * unlocked  [ unused:25 | hash:31 | -:1 | age:4 | b=0 | 01 ]
 * biased    [ thread:54 | epoch:2 | -:1 | age:4 | b=1 | 01 ]
 * }</pre></li>
 * <li><b>JDK 15</b>: 편향 락 기본 비활성화·deprecated(JEP 374). 요즘 코드는 경합 없는 락을 덜 쓰고, 취소 비용과 VM 코드 복잡도가 이득보다 크다고 판단했다.</li>
 * <li><b>JDK 18</b>: 편향 락 코드 제거. {@code b} 비트가 빈자리(위 배치의 [2])가 되었다.</li>
 * <li><b>JDK 21</b>: {@code -XX:LockingMode} 추가. 기본값 1(LM_LEGACY)이 위의 스택 락 방식이고, 2(LM_LIGHTWEIGHT)는 옵션이다.</li>
 * <li><b>JDK 23</b>: LM_LIGHTWEIGHT가 기본값. {@code 00} 상태에서도 hash와 age가 mark word에 그대로 남고,
 *     소유자는 Lock Record 포인터 대신 스레드 안의 작은 배열(lock stack)에 기록된다. mark word를 밖으로 옮기지 않으므로 hash 때문에 팽창하는 일이 줄어든다.</li>
 * <li><b>JDK 24</b>: {@code synchronized} 안에서 블로킹해도 가상 스레드가 캐리어를 붙잡지 않는다(JEP 491).
 *     Compact Object Headers(JEP 450)가 실험 기능으로 들어왔다. klass pointer를 mark word 안에 접어 넣어 헤더 전체가 8바이트가 된다.
 * <pre>{@code
 * compact   [ klass:22 | hash:31 | -:4 | age:4 | f:1 | lock:2 ]
 * }</pre></li>
 * <li><b>JDK 25</b>: Compact Object Headers가 정식 기능이 되었다(JEP 519). 기본값은 꺼져 있고 {@code -XX:+UseCompactObjectHeaders}로 켠다.</li>
 * </ul>
 *
 * <h2>OS 관점</h2>
 * 자바 플랫폼 스레드는 OS 스레드와 1:1이다. 모니터에서 잠드는 스레드는 HotSpot의 {@code ParkEvent}를 통해
 * Linux에서는 {@code pthread_cond_wait}(내부적으로 futex 시스템 콜), Windows에서는 이벤트 객체에 대한
 * {@code WaitForSingleObject}로 커널에 들어가 스케줄러 대기열에서 빠진다. 깨어날 때 컨텍스트 스위치 비용이 든다.
 *
 * <h2>예제</h2>
 * <ul>
 * <li>{@link com.gunha.lock.monitor.UnsafeCounter}: 락이 없을 때 생기는 갱신 유실(volatile로도 못 막음)</li>
 * <li>{@link com.gunha.lock.monitor.SynchronizedCounter}: {@code synchronized}의 상호 배제, 재진입, BLOCKED 상태</li>
 * <li>{@link com.gunha.lock.monitor.MonitorBoundedBuffer}: {@code wait/notifyAll}로 만든 생산자-소비자</li>
 * </ul>
 */
package com.gunha.lock.monitor;
