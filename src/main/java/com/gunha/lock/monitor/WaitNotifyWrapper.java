package com.gunha.lock.monitor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * {@link Object#wait()}/{@link Object#notifyAll()}을 감싼 래퍼. 이 둘로 크기 제한 버퍼(생산자-소비자)를 만든다.
 *
 * {@code synchronized} 메서드와 {@code wait()}/{@code notifyAll()}은 모두 버퍼 객체({@code this})의 모니터를 쓴다.
 * 모니터의 {@code _owner}, 입장 대기줄, {@code _WaitSet} 구조와 스레드가 그 사이를 오가는 그림은 {@code package-info.java}(이 패키지 설명)에 있다.
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
 * 모니터에는 WaitSet이 <b>하나</b>뿐이라, 가득 차서 기다리는 생산자와 비어서 기다리는 소비자가 한 집합에 섞여 있다.
 * {@code notify()}는 그중 <b>아무 스레드 하나를 한 번만</b> 깨운다. 누구를 깨울지 고를 수 없고, 맞지 않아도 다시 시도하지 않는다.
 * <ol>
 * <li>소비자가 값을 꺼낸 뒤 생산자를 깨우려고 {@code notify()}를 불렀는데, 다른 소비자가 깨어날 수 있다.</li>
 * <li>깨어난 소비자는 {@code while}로 확인해 보니 여전히 비어 있어 다시 {@code wait()}한다. 신호는 이 스레드가 써 버려서 사라진다.</li>
 * <li>깨어나야 했던 생산자는 계속 잠들어 있다. 이렇게 모든 스레드가 WaitSet에 들어가면 깨워 줄 스레드가 없어 영원히 멈춘다.</li>
 * </ol>
 * {@code notifyAll()}은 기다리는 스레드를 <b>한 번에 모두</b> 깨운다. 깨어난 스레드들은 모니터를 차례로 다시 잡고
 * {@code while}로 조건을 확인해, 조건이 맞는 쪽은 진행하고 아닌 쪽은 다시 {@code wait()}한다. 필요한 쪽이 반드시 깨어나므로 안전하다.
 * 대신 깨어날 필요가 없는 스레드까지 깨워 컨텍스트 스위치가 늘어난다(thundering herd).
 * {@code notifyAll()}은 깨우기를 반복하지 않는다. 한 번에 모두 입장 대기줄로 옮길 뿐이고, 줄에 선 스레드가 하나씩 들어와
 * 조건이 안 맞으면 다시 {@code wait()}으로 돌아간다. 돌아간 스레드는 다른 스레드가 다음에 부르는 {@code notifyAll()}을 기다린다.
 * <p>
 * 조건 큐를 나눠 필요한 쪽만 깨우는 해법이 {@link com.gunha.lock.locks.ConditionWrapper}다.
 *
 * <h2>신호는 저장되지 않는다</h2>
 * 기다리는 스레드가 없을 때 부른 {@code notify()}는 그냥 사라진다. 그래서 상태(여기서는 {@code items})를
 * 모니터 안에 두고 조건으로 확인해야 한다. {@link java.util.concurrent.locks.LockSupport}의 permit과 비교해 볼 것.
 *
 * <h2>테스트로 확인</h2>
 * 소비자는 {@code wait()} 중에 모니터를 놓으므로 생산자가 들어와 값을 넣을 수 있다.
 * {@snippet file="com/gunha/lock/monitor/WaitNotifyWrapperTest.java" region="wait-notify"}
 */
public class WaitNotifyWrapper<E> {

	private final Deque<E> items = new ArrayDeque<>();

	private final int capacity;

	public WaitNotifyWrapper(int capacity) {
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
