package com.gunha.lock.locks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link Condition} 두 개로 만든 크기 제한 버퍼.
 * {@link com.gunha.lock.monitor.MonitorBoundedBuffer}와 같은 일을 하므로 나란히 비교해 볼 것.
 *
 * <h2>Condition의 동작 (AQS ConditionObject)</h2>
 * <ul>
 * <li>{@code await()}: 락을 가진 상태에서만 부를 수 있다. 조건 큐에 노드를 넣고, 락을 완전히 놓은 뒤
 *     {@code park}로 잠든다. 상태는 {@code WAITING}.</li>
 * <li>{@code signal()}: 조건 큐의 첫 노드를 락의 대기 큐로 옮긴다. 깨어난 스레드는 락을 다시 얻어야
 *     {@code await()}에서 돌아온다. {@code Object.notify()}와 같은 구조다.</li>
 * <li>spurious wakeup은 여기서도 허용되므로 조건은 {@code while}로 확인한다.</li>
 * </ul>
 *
 * <h2>모니터 버전보다 나은 점: 조건 큐 분리</h2>
 * 모니터에는 WaitSet이 하나라 {@code notifyAll()}로 생산자·소비자를 모두 깨워야 했다.
 * 여기서는 "가득 차지 않음"({@code notFull})과 "비어 있지 않음"({@code notEmpty})을 따로 기다리므로,
 * 깨워야 할 쪽만 정확히 하나 깨우는 {@code signal()}로 충분하다. 쓸데없는 깨우기와 컨텍스트 스위치가 줄어든다.
 * JDK의 {@link java.util.concurrent.ArrayBlockingQueue}가 정확히 이 구조다.
 *
 * <h2>테스트로 확인</h2>
 * 소비자는 {@code notEmpty} 큐에서만 기다리고, {@code notFull} 큐는 비어 있다.
 * {@snippet file="com/gunha/lock/locks/ConditionBoundedBufferTest.java" region="condition-queue"}
 */
public class ConditionBoundedBuffer<E> {

	private final Deque<E> items = new ArrayDeque<>();

	private final int capacity;

	private final ReentrantLock lock = new ReentrantLock();

	private final Condition notFull = lock.newCondition();

	private final Condition notEmpty = lock.newCondition();

	public ConditionBoundedBuffer(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity는 1 이상이어야 합니다: " + capacity);
		}
		this.capacity = capacity;
	}

	public void put(E item) throws InterruptedException {
		lock.lock();
		try {
			while (items.size() == capacity) {
				notFull.await();
			}
			items.addLast(item);
			notEmpty.signal();
		}
		finally {
			lock.unlock();
		}
	}

	public E take() throws InterruptedException {
		lock.lock();
		try {
			while (items.isEmpty()) {
				notEmpty.await();
			}
			E item = items.removeFirst();
			notFull.signal();
			return item;
		}
		finally {
			lock.unlock();
		}
	}

	/** 빈 버퍼 때문에 기다리는 소비자가 있는지. ({@code hasWaiters}도 락을 가진 채 불러야 한다) */
	public boolean hasWaitingTakers() {
		lock.lock();
		try {
			return lock.hasWaiters(notEmpty);
		}
		finally {
			lock.unlock();
		}
	}

	/** 가득 찬 버퍼 때문에 기다리는 생산자가 있는지. */
	public boolean hasWaitingPutters() {
		lock.lock();
		try {
			return lock.hasWaiters(notFull);
		}
		finally {
			lock.unlock();
		}
	}

}
