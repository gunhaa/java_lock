package com.gunha.lock.monitor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * {@code wait/notifyAll}로 만든 크기 제한 버퍼(생산자-소비자).
 *
 * <h2>wait()이 하는 일</h2>
 * <ol>
 * <li>모니터를 가진 상태에서만 부를 수 있다. 아니면 {@link IllegalMonitorStateException}.</li>
 * <li>현재 스레드를 {@code ObjectMonitor}의 WaitSet에 넣고, 모니터를 <b>완전히</b> 놓는다(재진입 횟수까지 저장해 둔다).
 *     그래서 다른 스레드가 같은 모니터에 들어와 상태를 바꿀 수 있다.</li>
 * <li>스레드는 OS 대기로 들어가 {@code Thread.State.WAITING}이 된다.</li>
 * <li>{@code notify}를 받으면 WaitSet에서 진입 대기 큐로 옮겨질 뿐, 바로 실행되지 않는다.
 *     모니터를 다시 잡아야 {@code wait()}에서 돌아온다. 그 사이 상태는 {@code BLOCKED}다.</li>
 * </ol>
 *
 * <h2>왜 if가 아니라 while인가</h2>
 * <ul>
 * <li>깨어난 뒤 모니터를 다시 잡기 전에 다른 스레드가 먼저 들어와 조건을 바꿀 수 있다.</li>
 * <li>OS 수준에서 이유 없이 깨는 spurious wakeup이 허용된다(POSIX {@code pthread_cond_wait} 명세와 같음).</li>
 * </ul>
 * 그래서 깨어나면 조건을 반드시 다시 확인한다.
 *
 * <h2>왜 notify가 아니라 notifyAll인가</h2>
 * 모니터에는 WaitSet이 <b>하나</b>뿐이다. 생산자와 소비자가 같은 집합에서 기다리기 때문에,
 * {@code notify()}가 하나만 깨우면 엉뚱하게 같은 편(생산자가 생산자를)을 깨울 수 있다.
 * 그러면 신호가 유실되고 모두 잠들어 버릴 수 있다. {@code notifyAll()}은 안전한 대신 불필요한 스레드까지 깨워
 * 컨텍스트 스위치가 늘어난다(thundering herd). 조건 큐를 나누는 해법이 {@link com.gunha.lock.locks.ConditionBoundedBuffer}다.
 *
 * <h2>신호는 저장되지 않는다</h2>
 * 기다리는 스레드가 없을 때 부른 {@code notify()}는 그냥 사라진다. 그래서 상태(여기서는 {@code items})를
 * 모니터 안에 두고 조건으로 확인해야 한다. {@link java.util.concurrent.locks.LockSupport}의 permit과 비교해 볼 것.
 *
 * <h2>테스트로 확인</h2>
 * 소비자는 {@code wait()} 중에 모니터를 놓으므로 생산자가 들어와 값을 넣을 수 있다.
 * {@snippet file="com/gunha/lock/monitor/MonitorBoundedBufferTest.java" region="wait-notify"}
 */
public class MonitorBoundedBuffer<E> {

	private final Deque<E> items = new ArrayDeque<>();

	private final int capacity;

	public MonitorBoundedBuffer(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity는 1 이상이어야 합니다: " + capacity);
		}
		this.capacity = capacity;
	}

	public synchronized void put(E item) throws InterruptedException {
		while (items.size() == capacity) {
			wait();
		}
		items.addLast(item);
		notifyAll();
	}

	public synchronized E take() throws InterruptedException {
		while (items.isEmpty()) {
			wait();
		}
		E item = items.removeFirst();
		notifyAll();
		return item;
	}

	public synchronized int size() {
		return items.size();
	}

}
