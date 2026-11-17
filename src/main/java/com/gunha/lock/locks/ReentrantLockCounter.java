package com.gunha.lock.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link ReentrantLock}으로 보호한 카운터.
 *
 * <h2>AQS 위에서의 동작</h2>
 * <ul>
 * <li>{@code state == 0}이면 비어 있다. {@code lock()}은 CAS로 0 → 1을 시도하고, 성공하면 소유 스레드를 기록한다.</li>
 * <li>소유 스레드가 다시 {@code lock()}하면 CAS 없이 {@code state}만 1 늘린다(재진입). {@code getHoldCount()}가 이 값이다.</li>
 * <li>실패한 스레드는 대기 큐에 들어가 {@code LockSupport.park}로 잠든다. 상태는 {@code WAITING}이다
 *     ({@code synchronized}의 {@code BLOCKED}와 다르다).</li>
 * <li>{@code unlock()}은 {@code state}를 1 줄이고, 0이 되면 소유자를 지우고 큐의 다음 스레드를 {@code unpark}한다.</li>
 * </ul>
 *
 * <h2>공정(fair) vs 비공정(nonfair, 기본값)</h2>
 * <ul>
 * <li><b>비공정</b>: 새로 온 스레드가 큐를 무시하고 바로 CAS를 시도한다(barging). 마침 락이 비어 있으면 잠든 스레드를
 *     깨우고 OS가 스케줄링할 때까지 기다리는 시간(수 μs~수십 μs)을 아낄 수 있어 처리량이 높다. 대신 오래 기다린 스레드가
 *     계속 밀리는 기아(starvation)가 이론상 가능하다.</li>
 * <li><b>공정</b>: 큐에 앞선 대기자가 있으면({@code hasQueuedPredecessors()}) 줄을 선다. 순서가 보장되는 대신 락이
 *     넘어갈 때마다 깨우기와 컨텍스트 스위치가 일어나 많이 느리다.</li>
 * <li>주의: 인자 없는 {@code tryLock()}은 공정 락이어도 공정성을 무시하고 끼어든다.</li>
 * </ul>
 *
 * <h2>synchronized로는 안 되는 것</h2>
 * <ul>
 * <li>{@link #tryIncrement(long, TimeUnit)}: 정해진 시간만 기다리고 포기. 기다리는 동안 {@code TIMED_WAITING}.
 *     교착 회피나 응답 시간 상한이 필요할 때 쓴다.</li>
 * <li>{@link #incrementInterruptibly()}: 기다리는 중에 인터럽트되면 {@link InterruptedException}으로 빠져나온다.
 *     {@code synchronized} 진입 대기와 {@code lock()}은 인터럽트로 깨울 수 없다.</li>
 * </ul>
 *
 * <h2>반드시 지킬 형태</h2>
 * <pre>
 * lock.lock();          // try 밖에서: 획득에 실패했는데 finally에서 unlock하면 IllegalMonitorStateException
 * try {
 *     // 임계 구역
 * } finally {
 *     lock.unlock();    // 예외가 나도 반드시 해제. 빠뜨리면 영원히 잠긴다.
 * }
 * </pre>
 *
 * <h2>메모리 가시성</h2>
 * {@code Lock} 명세가 모니터와 같은 메모리 의미를 보장한다. 구현으로 보면 {@code state}가 volatile이라,
 * 해제 시 volatile 쓰기 → 다음 획득 시 volatile 읽기 사이에 happens-before가 성립한다.
 */
public class ReentrantLockCounter {

	private final ReentrantLock lock;

	private int count;

	public ReentrantLockCounter(boolean fair) {
		this.lock = new ReentrantLock(fair);
	}

	public void increment() {
		lock.lock();
		try {
			count++;
		}
		finally {
			lock.unlock();
		}
	}

	/** timeout 안에 락을 얻으면 증가시키고 true, 못 얻으면 false. */
	public boolean tryIncrement(long timeout, TimeUnit unit) throws InterruptedException {
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

	/** 락을 기다리다 인터럽트되면 증가시키지 않고 {@link InterruptedException}을 던진다. */
	public void incrementInterruptibly() throws InterruptedException {
		lock.lockInterruptibly();
		try {
			count++;
		}
		finally {
			lock.unlock();
		}
	}

	/** 락을 쥔 채 다시 락을 잡고, 안쪽에서 본 보유 횟수를 돌려준다(재진입 확인용). */
	public int nestedHoldCount() {
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
