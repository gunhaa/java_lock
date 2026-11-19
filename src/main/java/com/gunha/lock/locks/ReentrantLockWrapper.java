package com.gunha.lock.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link ReentrantLock}을 감싼 래퍼. 획득 방식({@code lock}, {@code tryLock}, {@code lockInterruptibly})마다 메서드를 두고,
 * 각각 락으로 보호한 카운터를 하나 늘린다.
 *
 * <h2>AQS 위에서의 동작</h2>
 * 공통 구조(CAS → sync queue → park)는 {@link com.gunha.lock.locks} 패키지 설명에 있다. {@code ReentrantLock}에서 {@code state}는 보유 횟수다.
 * <ul>
 * <li>{@code state == 0}이면 비어 있다. {@code lock()}은 CAS로 0 → 1을 시도하고, 성공하면 소유 스레드를 기록한다.</li>
 * <li>소유 스레드가 다시 {@code lock()}하면 CAS 없이 {@code state}만 1 늘린다(재진입). {@code getHoldCount()}가 이 값이다.</li>
 * <li>{@code unlock()}은 {@code state}를 1 줄이고, 0이 되면 소유자를 지우고 큐의 다음 스레드를 {@code unpark}한다.</li>
 * </ul>
 *
 * <h2>공정(fair) vs 비공정(nonfair, 기본값)</h2>
 * 두 모드 모두 {@code unlock()}은 다음 대기자에게 소유권을 넘겨주지 않는다(hand-off가 아니다). {@code state}를 0으로 만들고
 * sync queue 맨 앞 스레드를 깨울 뿐이라, 깨어난 스레드도 CAS로 직접 락을 얻어야 한다. 두 모드의 차이는 그 CAS에 누가 끼어드느냐다.
 * <pre>{@code
 * unlock(): state = 0, unpark the first thread in the sync queue
 *   woken first thread   --+
 *   newly arriving threads +--> CAS(state, 0 -> 1): exactly one wins
 * }</pre>
 * <ul>
 * <li><b>비공정</b>: 새로 온 스레드도 큐를 보지 않고 바로 CAS를 시도해, 깨어난 맨 앞 스레드와 <b>CAS로 동시에 경합</b>한다(barging).
 *     깨어난 스레드는 다시 실행되기까지 수 μs~수십 μs가 걸리지만 새로 온 스레드는 이미 실행 중이라, 대개 새로 온 쪽이 이긴다.
 *     그 틈에 락을 쓰고 나가므로 처리량이 높다. 진 쪽 중 새로 온 스레드는 큐 꼬리에 줄을 서고, 맨 앞 스레드는 자리를 지킨 채 다시 잠든다.
 *     그래서 오래 기다린 스레드가 계속 밀리는 기아(starvation)가 이론상 가능하다.</li>
 * <li><b>공정</b>: 새로 온 스레드는 CAS 전에 큐에 앞선 대기자가 있는지({@code hasQueuedPredecessors()}) 보고, 있으면 경합하지 않고 줄을 선다.
 *     맨 앞 스레드만 CAS하므로 순서가 보장되는 대신, 락이 넘어갈 때마다 깨우기 지연이 그대로 드러나 많이 느리다.</li>
 * <li>주의: 인자 없는 {@code tryLock()}은 공정 락이어도 공정성을 무시하고 끼어든다.</li>
 * </ul>
 *
 * <h2>synchronized로는 안 되는 것</h2>
 * <ul>
 * <li>{@link ReentrantLock#tryLock(long, TimeUnit)}: 정해진 시간만 기다리고 포기. 기다리는 동안 {@code TIMED_WAITING}.
 *     교착 회피나 응답 시간 상한이 필요할 때 쓴다.</li>
 * <li>{@link ReentrantLock#lockInterruptibly()}: 기다리는 중에 인터럽트되면 {@link InterruptedException}으로 빠져나온다.
 *     {@code synchronized} 진입 대기와 {@code lock()}은 인터럽트해도 포기하지 않고 계속 기다린다({@code lock()}은 인터럽트 플래그만 남긴다).</li>
 * </ul>
 *
 * <h2>반드시 지킬 형태</h2>
 * <pre>
 * lock.lock();          // try 밖에서: lock()이 예외로 끝났는데 try 안이었다면, finally의 unlock이 IllegalMonitorStateException을 던진다
 * try {
 *     // 임계 구역
 * } finally {
 *     lock.unlock();    // 예외가 나도 반드시 해제. 빠뜨리면 영원히 잠긴다.
 * }
 * </pre>
 *
 * <h2>메모리 가시성</h2>
 * {@code Lock} 명세가 모니터와 같은 메모리 의미를 보장한다(docs/02 2.2절). 구현으로 보면 {@code state}가 volatile이라
 * 해제의 volatile 쓰기와 다음 획득의 volatile 읽기 사이에 happens-before가 성립한다.
 *
 * <h2>테스트로 확인</h2>
 * 같은 경합이라도 AQS는 {@code park}로 재우므로 {@code WAITING}이다.
 * {@snippet file="com/gunha/lock/locks/ReentrantLockWrapperTest.java" region="waiting"}
 * {@code lockInterruptibly}로 기다리는 스레드는 인터럽트로 포기시킬 수 있다.
 * {@snippet file="com/gunha/lock/locks/ReentrantLockWrapperTest.java" region="interruptibly"}
 */
public class ReentrantLockWrapper {

	private final ReentrantLock lock;

	private int count;

	public ReentrantLockWrapper(boolean fair) {
		this.lock = new ReentrantLock(fair);
	}

	/** {@link ReentrantLock#lock()}으로 락을 얻고 증가시킨다. 얻을 때까지 기다리며, 인터럽트해도 포기하지 않는다. */
	public void incrementWithLock() {
		lock.lock();
		try {
			count++;
		}
		finally {
			lock.unlock();
		}
	}

	/** {@link ReentrantLock#tryLock(long, TimeUnit)}으로 timeout 안에 락을 얻으면 증가시키고 true, 못 얻으면 false. */
	public boolean incrementWithTryLock(long timeout, TimeUnit unit) throws InterruptedException {
		if (!lock.tryLock(timeout, unit)) {
			return false;
		}
		try {
			count++;
			return true;
		}
		finally {
			lock.unlock();
		}
	}

	/** {@link ReentrantLock#lockInterruptibly()}로 락을 얻고 증가시킨다. 기다리다 인터럽트되면 증가시키지 않고 {@link InterruptedException}을 던진다. */
	public void incrementWithLockInterruptibly() throws InterruptedException {
		lock.lockInterruptibly();
		try {
			count++;
		}
		finally {
			lock.unlock();
		}
	}

	/** 락을 쥔 채 다시 락을 잡고, 안쪽에서 본 {@link ReentrantLock#getHoldCount()}를 돌려준다(재진입 확인용). */
	public int getHoldCountWhenNested() {
		lock.lock();
		try {
			return innerHoldCount();
		}
		finally {
			lock.unlock();
		}
	}

	private int innerHoldCount() {
		lock.lock();
		try {
			return lock.getHoldCount();
		}
		finally {
			lock.unlock();
		}
	}

	/** 락을 쥔 채 action을 실행한다. */
	public void runWhileLocked(Runnable action) {
		lock.lock();
		try {
			action.run();
		}
		finally {
			lock.unlock();
		}
	}

	/** {@link ReentrantLock#isFair()}에 위임한다. */
	public boolean isFair() {
		return lock.isFair();
	}

	public int get() {
		lock.lock();
		try {
			return count;
		}
		finally {
			lock.unlock();
		}
	}

}
