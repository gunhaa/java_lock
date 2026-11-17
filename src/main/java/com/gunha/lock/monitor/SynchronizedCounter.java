package com.gunha.lock.monitor;

/**
 * {@code synchronized}로 보호한 카운터. 모니터 락의 상호 배제·재진입·대기 상태를 보여 준다.
 *
 * <h2>바이트코드</h2>
 * <ul>
 * <li>메서드에 붙이면({@link #increment()}) 메서드 플래그에 {@code ACC_SYNCHRONIZED}가 켜진다.
 *     호출할 때 JVM이 {@code this}(static 메서드면 {@code Class} 객체)의 모니터를 잡는다.</li>
 * <li>블록으로 쓰면({@link #runWhileLocked(Runnable)}) javac가 {@code monitorenter}/{@code monitorexit}을 넣는다.
 *     예외로 빠져나가는 경로용 {@code monitorexit}도 예외 테이블에 하나 더 만든다.
 *     그래서 {@code synchronized}는 락 해제를 잊을 수 없다.</li>
 * </ul>
 * 확인: {@code ./gradlew compileJava} 후
 * {@code javap -c -v build/classes/java/main/com/gunha/lock/monitor/SynchronizedCounter.class}
 *
 * <h2>객체 헤더와 락 상태 (HotSpot, JDK 21 기본 설정)</h2>
 * 64비트 JVM에서 모든 객체 헤더 앞부분에는 8바이트 mark word가 있고, 하위 비트가 락 상태를 나타낸다.
 * <ol>
 * <li><b>잠기지 않음</b> ({@code 01}): mark word에 해시코드, GC 나이가 들어 있다.</li>
 * <li><b>스택 락 / thin lock</b> ({@code 00}): 경합이 없을 때. 소유 스레드가 자기 스택에 lock record를 만들고
 *     원래 mark word를 거기 복사한 뒤, CAS로 mark word가 lock record를 가리키게 한다.
 *     CAS 한 번(x86 {@code lock cmpxchg})이면 끝나고 OS는 관여하지 않는다.</li>
 * <li><b>팽창 / inflated</b> ({@code 10}): 다른 스레드가 같은 락을 원하거나 {@code wait()}을 부르면
 *     {@code ObjectMonitor}를 만들어 mark word가 그것을 가리키게 한다. {@code ObjectMonitor}는
 *     소유자({@code _owner}), 재진입 횟수({@code _recursions}), 진입 대기 큐({@code _cxq}, {@code _EntryList}),
 *     {@code wait()} 대기 집합({@code _WaitSet})을 가진다.</li>
 * </ol>
 * 진입을 기다리는 스레드는 먼저 적응형 스핀(adaptive spinning)으로 잠깐 버틴다. 그래도 못 잡으면
 * OS 대기(Linux futex, Windows 이벤트)로 들어가 {@code Thread.State.BLOCKED}가 된다.
 * <p>
 * 참고: 편향 락(biased locking)은 JDK 15에서 기본 비활성화, JDK 18에서 제거되어 21에는 없다.
 * 스레드별 lock stack을 쓰는 lightweight locking({@code -XX:LockingMode=2})은 JDK 21에서는 옵션이고, JDK 23부터 기본값이다.
 *
 * <h2>재진입</h2>
 * 이미 락을 가진 스레드가 같은 모니터에 다시 들어가면 막히지 않고 재진입 횟수만 늘어난다.
 * {@link #incrementTwice()}가 안에서 {@link #increment()}를 불러도 교착되지 않는 이유다.
 *
 * <h2>메모리 가시성</h2>
 * 모니터 해제는 같은 모니터의 이후 획득보다 happens-before다. 그래서 {@code count}에 volatile이 없어도
 * 락을 잡은 스레드는 항상 직전 소유자가 쓴 값을 본다. JIT은 획득·해제 지점에 재정렬을 막는 배리어를 넣는다.
 *
 * <h2>Java 21 가상 스레드 주의</h2>
 * 가상 스레드가 {@code synchronized} 안에서 블로킹(I/O, sleep, 다른 락 대기)되면, 그 가상 스레드를 실행하던
 * 캐리어(OS) 스레드도 함께 묶인다(pinning). 모니터 소유권이 OS 스레드 기준으로 기록되기 때문이다.
 * {@code -Djdk.tracePinnedThreads=full}로 확인할 수 있고, JDK 24(JEP 491)에서 해결되었다.
 * 21에서 가상 스레드를 쓴다면 {@link com.gunha.lock.locks.ReentrantLockCounter} 쪽이 안전하다.
 */
public class SynchronizedCounter {

	private int count;

	public synchronized void increment() {
		count++;
	}

	/** 락을 쥔 채 같은 락이 필요한 메서드를 부른다. 재진입이 되므로 교착되지 않는다. */
	public synchronized void incrementTwice() {
		increment();
		increment();
	}

	/** 현재 스레드가 이 객체의 모니터를 가지고 있는지. */
	public boolean isHeldByCurrentThread() {
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
