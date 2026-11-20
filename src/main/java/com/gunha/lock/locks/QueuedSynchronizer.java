package com.gunha.lock.locks;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * {@link LockSupport}와 큐로 직접 만든 미니 AQS. 배타 락 하나만 지원한다.
 * JDK {@code LockSupport} 문서의 {@code FIFOMutex} 예제를 바탕으로, 메서드를 AQS와 같은 모양으로 나눴다.
 * 실무에서는 쓰지 않는다. {@code java.util.concurrent.locks}의 모든 락이 이 구조를 어떻게 쌓았는지 보려는 예제다.
 *
 * <h2>재료: LockSupport의 API는 둘뿐이다</h2>
 * <ul>
 * <li>{@code park()}: <b>자기 자신</b>을 멈춘다. 스레드 인자가 없으므로 남을 재울 수는 없다.</li>
 * <li>{@code unpark(thread)}: <b>특정 스레드</b>에게 permit을 준다. 그 스레드가 자고 있으면 깨어난다.</li>
 * </ul>
 * permit은 스레드마다 1비트다. {@code park()}는 permit이 있으면 소비하고 <b>즉시 돌아오고</b>(잠들지 않는다), 없으면 잠든다.
 * {@code unpark}를 여러 번 불러도 1을 넘지 않는다.
 * 그래서 <b>{@code unpark}가 {@code park}보다 먼저 와도 신호가 유실되지 않는다.</b> 대기자가 없으면 사라지는 {@code Object.notify()}와 다른 점이다.
 * <p>
 * JDK 문서는 이 두 메서드를 "고수준 동기화 도구를 만들기 위한 도구이고, 대부분의 동시성 제어에는 그 자체로 유용하지 않다"고 설명한다.
 * 조건, 대기자 목록, 깨울 순서, 인터럽트 처리를 <b>전부 호출자가 책임져야</b> 하기 때문이다. 이 클래스가 그 책임을 진 모습이다.
 *
 * <h2>호출자 책임 = AQS의 각 부분</h2>
 * <table class="striped">
 * <caption>이 클래스의 코드와 AQS의 대응</caption>
 * <thead>
 * <tr><th scope="col">호출자 책임</th><th scope="col">이 클래스</th><th scope="col">AQS</th></tr>
 * </thead>
 * <tbody>
 * <tr><th scope="row">상태는 따로 관리한다 (permit은 조건이 아니다)</th><td>{@code state} CAS, {@code tryAcquire}/{@code tryRelease}</td><td>{@code state} CAS, 하위 클래스의 {@code tryAcquire}/{@code tryRelease}</td></tr>
 * <tr><th scope="row">누구를 깨울지 알아야 한다</th><td>{@code queue}({@code Thread}를 담은 FIFO 큐)</td><td>sync queue(CLH 변형 노드 리스트)</td></tr>
 * <tr><th scope="row">깨어나도 다시 확인한다 (spurious wakeup)</th><td>{@code acquire}의 {@code while} 루프</td><td>{@code acquire}의 재시도 루프</td></tr>
 * <tr><th scope="row">인터럽트를 어떻게 다룰지 정한다</th><td>무시하고 계속 기다린 뒤 플래그만 복원</td><td>{@code acquire}는 같고, {@code acquireInterruptibly}는 예외</td></tr>
 * <tr><th scope="row">깨울 순서를 정한다</th><td>큐 맨 앞만 획득 시도(항상 공정)</td><td>공정 모드는 같고, 비공정 모드는 새 스레드의 끼어들기 허용</td></tr>
 * </tbody>
 * </table>
 * permit이 필요한 이유도 여기서 드러난다. 대기자가 큐에 들어가 {@code park}하기 직전에 다른 스레드가 {@code release}하면
 * {@code unpark}가 먼저 온다. permit이 남아 있으므로 대기자는 {@code park}에서 바로 돌아와 다시 확인한다.
 *
 * <h2>AQS에만 있는 것</h2>
 * 이 클래스와 진짜 AQS의 차이가 곧 AQS가 복잡한 이유다.
 * <ul>
 * <li>소유자와 재진입: 이 클래스는 누가 쥐었는지 모르므로 재진입하면 교착되고, 다른 스레드가 {@code release}해도 막지 못한다.</li>
 * <li>비공정 끼어들기: 새 스레드도 일단 큐에 들어가므로 끼어들 수 없다. 처리량은 비공정 쪽이 높다.</li>
 * <li>타임아웃과 취소: 기다리다 포기한 노드를 큐에서 빼는 처리.</li>
 * <li>공유 모드: {@code Semaphore}, {@code CountDownLatch}처럼 여러 스레드가 함께 통과하는 모드.</li>
 * <li>{@code Condition}과 노드 상태: 조건 대기 큐, 그리고 깨울 필요가 없을 때 {@code unpark}를 건너뛰는 최적화.</li>
 * </ul>
 *
 * <h2>JVM·OS 관점</h2>
 * {@code LockSupport.park} → {@code Unsafe.park} → 스레드마다 하나 있는 HotSpot {@code Parker}. {@code Parker}의 {@code _counter}가 permit이고,
 * 잠드는 것은 OS 대기다(Linux {@code pthread_cond_wait} → futex, Windows {@code WaitForSingleObject},
 * <a href="https://github.com/gunhaa/java_lock/blob/main/docs/03-hardware-and-os.md#futex">docs/03 3.7절</a>).
 * 플랫폼 스레드의 상태는 {@code WAITING}이고, 가상 스레드는 OS 스레드를 재우지 않고 캐리어에서 내려온다.
 * {@code park(this)}처럼 blocker를 넘기면 스레드 덤프에 무엇을 기다리는지 찍힌다.
 *
 * <h2>테스트로 확인</h2>
 * 대기자는 큐에 들어온 순서대로 락을 얻는다.
 * {@snippet file="com/gunha/lock/locks/QueuedSynchronizerTest.java" region="fifo"}
 */
public class QueuedSynchronizer {

	static {
		// JDK 예제 그대로: 처음 park/unpark할 때 클래스 로딩이 끼어 unpark를 잃을 위험을 줄인다.
		@SuppressWarnings("unused")
		Class<?> ensureLoaded = LockSupport.class;
	}

	/** 0이면 비어 있고 1이면 잡혀 있다. */
	private final AtomicInteger state = new AtomicInteger();

	/** sync queue. 기다리는 스레드를 도착 순서대로 담는다. */
	private final Queue<Thread> queue = new ConcurrentLinkedQueue<>();

	/** 락을 얻을 때까지 기다린다. 인터럽트되어도 계속 기다리고, 얻은 뒤 인터럽트 플래그를 복원한다. */
	public void acquire() {
		Thread current = Thread.currentThread();
		boolean interrupted = false;
		// 깨워 줄 쪽이 찾을 수 있게 먼저 큐에 올린다.
		queue.add(current);
		// 큐 맨 앞이 아니거나 상태 CAS에 지면 잠든다. 깨어나면(unpark, spurious, 인터럽트) 다시 확인한다.
		while (queue.peek() != current || !tryAcquire()) {
			LockSupport.park(this);
			// 플래그를 지워 두지 않으면 다음 park가 바로 돌아와 바쁜 대기가 된다.
			if (Thread.interrupted()) {
				interrupted = true;
			}
		}
		queue.remove();
		if (interrupted) {
			current.interrupt();
		}
	}

	/** 락을 놓고 큐 맨 앞 스레드를 깨운다. 큐가 비어 있으면 {@code unpark(null)}은 아무 일도 하지 않는다. */
	public void release() {
		if (tryRelease()) {
			LockSupport.unpark(queue.peek());
		}
	}

	/** 상태 규칙: 비어 있으면 CAS로 차지한다. AQS에서는 이 메서드를 하위 클래스가 정의한다. */
	protected boolean tryAcquire() {
		return state.compareAndSet(0, 1);
	}

	/** 상태 규칙: 비운다. 완전히 풀려 대기자를 깨워야 하면 true. AQS에서는 이 메서드를 하위 클래스가 정의한다. */
	protected boolean tryRelease() {
		state.set(0);
		return true;
	}

	public boolean isLocked() {
		return state.get() != 0;
	}

	/** 큐에 있는 스레드 수. 맨 앞 스레드가 막 깨어 나가는 중이면 그 스레드도 포함한다. */
	public int getQueueLength() {
		return queue.size();
	}

}
