package com.gunha.lock.locks;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class QueuedSynchronizerTest {

	private int count;

	@Test
	@DisplayName("unpark가 park보다 먼저 와도 permit이 남아 있어 park가 바로 돌아온다")
	void unparkBeforePark() {
		LockSupport.unpark(Thread.currentThread());

		// permit이 없었다면 여기서 영원히 잠들어 @Timeout으로 실패한다.
		LockSupport.park();
	}

	@Test
	@DisplayName("permit은 쌓이지 않는다: unpark를 두 번 해도 park는 한 번만 통과한다")
	void permitDoesNotAccumulate() throws Exception {
		AtomicBoolean go = new AtomicBoolean();
		Started<Void> parker = TestThreads.start("parker", () -> {
			// unpark 두 번이 다 끝난 뒤에 park하도록 기다린다. 래치로 기다리면 WAITING이 되어 아래 확인과 섞이므로 스핀한다.
			while (!go.get()) {
				Thread.onSpinWait();
			}
			LockSupport.park(); // permit을 소비하고 바로 돌아온다
			LockSupport.park(); // permit이 없으므로 잠든다
			return null;
		});
		LockSupport.unpark(parker.thread());
		LockSupport.unpark(parker.thread());
		go.set(true);

		// 두 번째 unpark가 permit을 2로 만들었다면 두 번째 park도 통과해 스레드가 끝났을 것이다.
		TestThreads.awaitState(parker.thread(), Thread.State.WAITING);

		LockSupport.unpark(parker.thread());
		parker.join();
	}

	@Test
	@DisplayName("여러 스레드가 동시에 증가시켜도 갱신 유실이 없다")
	void mutualExclusion() throws Exception {
		QueuedSynchronizer sync = new QueuedSynchronizer();

		TestThreads.runConcurrently(4, 10_000, () -> {
			sync.acquire();
			try {
				count++;
			}
			finally {
				sync.release();
			}
		});

		assertThat(count).isEqualTo(40_000);
		assertThat(sync.isLocked()).isFalse();
	}

	@Test
	@DisplayName("대기자는 큐에 들어온 순서대로 락을 얻는다 (FIFO)")
	void acquiresInQueueOrder() throws Exception {
		// @start region="fifo"
		QueuedSynchronizer sync = new QueuedSynchronizer();
		Queue<String> order = new ConcurrentLinkedQueue<>();
		sync.acquire();

		List<Started<Void>> waiters = new ArrayList<>();
		for (String name : List.of("T1", "T2", "T3")) {
			Started<Void> waiter = TestThreads.start(name, () -> {
				sync.acquire();
				order.add(name);
				sync.release();
				return null;
			});
			// 큐에 들어가 park할 때까지 기다린 뒤 다음 스레드를 출발시켜 큐 순서를 T1, T2, T3로 고정한다.
			TestThreads.awaitState(waiter.thread(), Thread.State.WAITING);
			waiters.add(waiter);
		}
		assertThat(sync.getQueueLength()).isEqualTo(3);

		// 놓으면 맨 앞 T1만 깨운다. T1이 놓을 때 T2를, T2가 놓을 때 T3를 깨운다.
		sync.release();
		for (Started<Void> waiter : waiters) {
			waiter.join();
		}
		assertThat(order).containsExactly("T1", "T2", "T3");
		// @end
	}

	@Test
	@DisplayName("인터럽트해도 계속 기다리고, 락을 얻은 뒤 인터럽트 플래그를 복원한다")
	void interruptIsIgnoredButRestored() throws Exception {
		QueuedSynchronizer sync = new QueuedSynchronizer();
		sync.acquire();
		Started<Boolean> waiter = TestThreads.start("waiter", () -> {
			sync.acquire();
			sync.release();
			return Thread.currentThread().isInterrupted();
		});
		TestThreads.awaitState(waiter.thread(), Thread.State.WAITING);

		waiter.thread().interrupt();
		// 인터럽트로 깨어나도 락이 잡혀 있으므로 예외 없이 다시 잠든다.
		TestThreads.awaitState(waiter.thread(), Thread.State.WAITING);
		assertThat(waiter.thread().isAlive()).isTrue();

		sync.release();
		assertThat(waiter.join()).isTrue();
	}

}
