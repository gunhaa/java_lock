package com.gunha.lock.locks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link Condition}을 감싼 래퍼. 한 {@link ReentrantLock}에서 만든 {@code Condition} 두 개({@code notFull}, {@code notEmpty})로
 * 크기 제한 버퍼(생산자-소비자)를 만든다.
 * {@link com.gunha.lock.monitor.WaitNotifyWrapper}와 같은 일을 하므로 나란히 비교해 볼 것.
 *
 * <h2>큐 구조 (AQS ConditionObject, JDK 21)</h2>
 * 락 하나에 큐가 두 종류 있다. 락을 기다리는 <b>sync queue</b> 하나와, {@code newCondition()}마다 하나씩 생기는 <b>condition queue</b>다.
 * <pre>{@code
 * ReentrantLock (AQS)
 *   state = 1, exclusiveOwnerThread = T1
 *
 *   sync queue (doubly linked)
 *   head -> [T3] <-> [T4] <- tail                   waiting for lock()
 *
 *   notEmpty: condition queue (singly linked, FIFO)
 *   firstWaiter -> [C1] -> [C2] <- lastWaiter       await() in take()
 *
 *   notFull:  condition queue (singly linked, FIFO)
 *   firstWaiter -> [P1] <- lastWaiter               await() in put()
 * }</pre>
 * 모니터({@link com.gunha.lock.monitor} 패키지 설명의 ObjectMonitor 그림)와 하나씩 대응한다.
 * <ul>
 * <li>{@code _owner} → {@code exclusiveOwnerThread}와 {@code state}(재진입 횟수)</li>
 * <li>입장 대기줄({@code _cxq}/{@code _EntryList}) → sync queue</li>
 * <li>{@code _WaitSet}(객체당 <b>하나</b>) → condition queue({@code Condition}마다 <b>하나</b>)</li>
 * </ul>
 *
 * <h2>await()이 하는 일</h2>
 * <ol>
 * <li>락을 가졌는지 확인한다. 아니면 {@link IllegalMonitorStateException}.</li>
 * <li>자기 노드를 condition queue 꼬리({@code lastWaiter})에 붙인다. 락을 쥔 상태라 CAS 없이 연결만 한다.</li>
 * <li>현재 {@code state}(재진입 횟수)를 저장하고 락을 <b>완전히</b> 놓는다. 이 해제로 sync queue의 다음 스레드가 깨어난다.</li>
 * <li>자기 노드가 sync queue로 옮겨질 때까지 {@code LockSupport.park()}로 잠든다. 상태는 {@code WAITING}.</li>
 * <li>옮겨진 뒤 깨어나면 저장해 둔 재진입 횟수로 락을 다시 얻는다. 차례가 아니면 sync queue에서 다시 잠든다.</li>
 * </ol>
 *
 * <h2>signal()이 하는 일</h2>
 * {@code firstWaiter}(가장 오래 기다린 노드) 하나를 condition queue에서 떼어 sync queue 꼬리로 <b>옮긴다</b>.
 * 보통 이 시점에는 깨우지 않는다. {@code signal()}을 부른 스레드가 아직 락을 쥐고 있으므로, 그 스레드가 {@code unlock()}할 때 깨어난다.
 * {@code signalAll()}은 condition queue의 노드를 앞에서부터 전부 순서대로 옮긴다.
 * <pre>{@code
 * signal() on notEmpty
 *   notEmpty:   firstWaiter -> [C2]                       C1 removed
 *   sync queue: head -> [T3] <-> [T4] <-> [C1] <- tail    C1 appended (still parked)
 * }</pre>
 *
 * <h2>결국 park/unpark를 다루는 방법</h2>
 * {@code await()}와 {@code signal()}을 걷어 내면 바닥에는 {@link java.util.concurrent.locks.LockSupport#park() LockSupport.park()}/{@link java.util.concurrent.locks.LockSupport#unpark(Thread) unpark()}만 남는다.
 * {@code Condition}은 그 위에 네 가지를 얹은 것이다.
 * <ul>
 * <li><b>누구를 깨울지</b>: 기다리는 스레드를 condition queue에 FIFO로 기록해 둔다. {@code unpark(t)}만 쓰려면 깨울 스레드를 직접 알아야 한다.</li>
 * <li><b>락과의 연동</b>: 잠들기 전에 락을 완전히 놓고, 깨어난 뒤 같은 재진입 횟수로 다시 얻는다. 락을 쥔 채 park하면 아무도 상태를 바꾸지 못해 교착된다.</li>
 * <li><b>깨우는 시점</b>: {@code signal()}은 sync queue로 옮기기만 하고, 실제 {@code unpark}는 {@code unlock()} 때 한다. 바로 깨우면 락을 못 얻고 다시 잠드는 헛걸음이 된다.</li>
 * <li><b>이상한 깨어남 처리</b>: park에서 돌아오면 자기 노드가 정말 옮겨졌는지 확인하고, 아니면 다시 park한다.
 *     park는 spurious wakeup이나 인터럽트로도 돌아오기 때문이다. 인터럽트와 시간 초과({@code parkNanos})도 여기서 구분한다.</li>
 * </ul>
 *
 * <h2>순서는 어디까지 보장되나</h2>
 * <ul>
 * <li><b>condition queue → sync queue로 옮기는 순서</b>: FIFO. AQS 문서가 {@code signal()}은 가장 오래 기다린 스레드를 옮긴다고 명시한다.
 *     {@code Object.notify()}가 "임의의 하나"인 것과 다르다.</li>
 * <li><b>sync queue에서 락을 얻는 순서</b>: 이 클래스가 쓰는 비공정 락은 보장하지 않는다. 막 {@code lock()}을 부른 스레드가
 *     큐를 건너뛰고 CAS로 먼저 얻을 수 있다. 공정 락({@code new ReentrantLock(true)})이면 FIFO다.</li>
 * </ul>
 * 그래서 {@code await()}에서 돌아와도 조건이 그대로라는 보장이 없고, spurious wakeup도 허용되므로 조건은 {@code while}로 확인한다.
 *
 * <h2>모니터 버전보다 나은 점: 조건 큐 분리</h2>
 * 모니터에는 WaitSet이 하나라 {@code notifyAll()}로 생산자·소비자를 모두 깨워야 했다.
 * 여기서는 "가득 차지 않음"({@code notFull})과 "비어 있지 않음"({@code notEmpty})을 따로 기다리므로,
 * 깨워야 할 쪽만 정확히 하나 깨우는 {@code signal()}로 충분하다. 쓸데없는 깨우기와 컨텍스트 스위치가 줄어든다.
 * JDK의 {@link java.util.concurrent.ArrayBlockingQueue}가 정확히 이 구조다.
 *
 * <h2>테스트로 확인</h2>
 * 소비자는 {@code notEmpty} 큐에서만 기다리고, {@code notFull} 큐는 비어 있다.
 * {@snippet file="com/gunha/lock/locks/ConditionWrapperTest.java" region="condition-queue"}
 */
public class ConditionWrapper<E> {

	private final Deque<E> items = new ArrayDeque<>();

	private final int capacity;

	private final ReentrantLock lock = new ReentrantLock();

	private final Condition notFull = lock.newCondition();

	private final Condition notEmpty = lock.newCondition();

	public ConditionWrapper(int capacity) {
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

	/** {@code notEmpty}에서 기다리는 스레드(빈 버퍼 때문에 기다리는 소비자)가 있는지. ({@code hasWaiters}도 락을 가진 채 불러야 한다) */
	public boolean hasNotEmptyWaiters() {
		lock.lock();
		try {
			return lock.hasWaiters(notEmpty);
		}
		finally {
			lock.unlock();
		}
	}

	/** {@code notFull}에서 기다리는 스레드(가득 찬 버퍼 때문에 기다리는 생산자)가 있는지. */
	public boolean hasNotFullWaiters() {
		lock.lock();
		try {
			return lock.hasWaiters(notFull);
		}
		finally {
			lock.unlock();
		}
	}

}
