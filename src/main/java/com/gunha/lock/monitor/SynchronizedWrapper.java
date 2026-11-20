package com.gunha.lock.monitor;

/**
 * {@code synchronized}(모니터 락)를 감싼 래퍼. 카운터 하나를 보호하며, 모니터 락의 상호 배제·재진입과 기다리는 스레드의 {@code BLOCKED} 상태를 보여 준다.
 *
 * <h2>바이트코드</h2>
 * <ul>
 * <li>메서드에 붙이면({@code synchronized void m()}) 메서드 플래그에 {@code ACC_SYNCHRONIZED}가 켜진다.
 *     호출할 때 JVM이 {@code this}(static 메서드면 {@code Class} 객체)의 모니터를 잡는다.</li>
 * <li>블록으로 쓰면({@code synchronized (obj) { ... }}) javac가 {@code monitorenter}/{@code monitorexit}을 넣는다.
 *     예외가 나도 {@code monitorexit}을 실행하는 핸들러 코드를 하나 더 만들고, 예외 테이블에 그 범위를 등록한다.
 *     그래서 {@code synchronized}는 락 해제를 잊을 수 없다.</li>
 * </ul>
 * 확인: {@code ./gradlew compileJava} 후
 * {@code javap -c -v build/classes/java/main/com/gunha/lock/monitor/SynchronizedWrapper.class}
 *
 * <h2>락 상태</h2>
 * 락 상태는 객체 헤더의 mark word에 기록된다. 비트 배치, 스택 락에서 팽창으로 넘어가는 과정, JDK 버전별 변화는
 * {@linkplain com.gunha.lock.monitor##object-header monitor 패키지의 객체 헤더 절}에 그림으로 정리했다.
 *
 * <h2>경합이 몰리면 어디서 기다리나</h2>
 * 여러 스레드가 동시에 {@code synchronized}에 들어오면 하나만 {@code _owner}가 되고, 나머지는 <b>모두 {@code _cxq}</b>(이후 {@code _EntryList})에서
 * {@code BLOCKED}로 기다린다. {@code _WaitSet}에는 가지 않는다. {@code _WaitSet}은 락을 이미 가진 스레드가 {@code wait()}을 불러 스스로 들어가는 곳이라,
 * 락 경합만으로는 비어 있다({@link com.gunha.lock.monitor.WaitNotifyWrapper}).
 * <pre>{@code
 * T1, T2, T3, T4 call increment() at the same time (after inflation)
 *   T1      CAS wins                   -> _owner                    RUNNABLE
 *   T2..T4  CAS fails -> spin -> park  -> _cxq (later _EntryList)   BLOCKED
 *   _WaitSet: empty (nobody called wait())
 * }</pre>
 * <ul>
 * <li>경합이 없으면 {@code ObjectMonitor}도 없다(스택 락). 다른 스레드가 오면 그때 팽창해 {@code _cxq}/{@code _EntryList}가 생긴다.</li>
 * <li>깨어나는 순서는 도착 순서가 아니다. {@code _cxq}와 {@code _EntryList}의 차이와 순서 규칙은
 *     {@linkplain com.gunha.lock.monitor##cxq-entrylist monitor 패키지의 _cxq와 _EntryList 절}에 있다.</li>
 * <li>스레드 덤프에서 구분: {@code _cxq}/{@code _EntryList}면 {@code BLOCKED (on object monitor)}와 {@code waiting to lock <0x...>},
 *     {@code _WaitSet}이면 {@code WAITING (on object monitor)}와 {@code waiting on <0x...>}다.</li>
 * <li>{@link com.gunha.lock.locks.ReentrantLockWrapper}도 구조는 같다. 다른 점은 기다리다 포기하기, 순서 정하기처럼
 *     호출자가 대기를 제어할 수 있느냐다({@linkplain com.gunha.lock.locks##monitor-vs-aqs locks 패키지의 비교표}).</li>
 * </ul>
 *
 * <h2>재진입</h2>
 * 이미 락을 가진 스레드가 같은 모니터에 다시 들어가면 막히지 않고 재진입 횟수만 늘어난다.
 * {@code synchronized} 메서드 안에서 같은 객체의 다른 {@code synchronized} 메서드를 불러도 교착되지 않는 이유다.
 *
 * <h2>메모리 가시성</h2>
 * 모니터 해제는 같은 모니터의 이후 획득보다 happens-before다(<a href="https://github.com/gunhaa/java_lock/blob/main/docs/02-java-memory-model.md#happens-before">docs/02 2.2절</a>). 그래서 {@code count}에 volatile이 없어도
 * 락을 잡은 스레드는 항상 직전 소유자가 쓴 값을 본다.
 *
 * <h2>Java 21 가상 스레드 주의</h2>
 * 가상 스레드가 {@code synchronized} 안에서 블로킹(I/O, sleep, 다른 락 대기)되면, 그 가상 스레드를 실행하던
 * 캐리어(OS) 스레드도 함께 묶인다(pinning). JDK 24(JEP 491)에서 해결되었다. 원인과 확인 방법은 <a href="https://github.com/gunhaa/java_lock/blob/main/docs/01-thread-model.md#virtual-thread">docs/01 1.7절</a>.
 * 21에서 가상 스레드를 쓴다면 {@link com.gunha.lock.locks.ReentrantLockWrapper} 쪽이 안전하다.
 *
 * <h2>테스트로 확인</h2>
 * 모니터를 기다리는 스레드는 {@code BLOCKED}가 된다.
 * {@snippet file="com/gunha/lock/monitor/SynchronizedWrapperTest.java" region="blocked"}
 * 여러 스레드가 몰려도 모두 {@code BLOCKED}다. {@code _WaitSet}에 있다면 {@code WAITING}이어야 한다.
 * {@snippet file="com/gunha/lock/monitor/SynchronizedWrapperTest.java" region="blocked-many"}
 */
public class SynchronizedWrapper {

	private int count;

	public synchronized void increment() {
		count++;
	}

	/** 락을 쥔 채 같은 락이 필요한 메서드를 부른다. 재진입이 되므로 교착되지 않는다. */
	public synchronized void incrementTwice() {
		increment();
		increment();
	}

	/** {@link Thread#holdsLock(Object)}에 위임한다. 현재 스레드가 이 객체의 모니터를 가지고 있는지. */
	public boolean holdsLock() {
		return Thread.holdsLock(this);
	}

	/** 모니터를 쥔 채 action을 실행한다. 블록 형태라 {@code monitorenter}/{@code monitorexit}으로 컴파일된다. */
	public void runWhileLocked(Runnable action) {
		synchronized (this) {
			action.run();
		}
	}

	public synchronized int get() {
		return count;
	}

}
