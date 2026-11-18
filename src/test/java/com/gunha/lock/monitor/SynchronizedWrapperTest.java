package com.gunha.lock.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class SynchronizedWrapperTest {

	@Test
	@DisplayName("synchronized로 보호하면 동시 증가에도 값이 유실되지 않는다")
	void mutualExclusion() throws InterruptedException {
		SynchronizedWrapper wrapper = new SynchronizedWrapper();

		TestThreads.runConcurrently(8, 100_000, wrapper::increment);

		assertThat(wrapper.get()).isEqualTo(8 * 100_000);
	}

	@Test
	@DisplayName("락을 가진 스레드는 같은 모니터에 다시 들어갈 수 있다(재진입)")
	void reentrant() {
		SynchronizedWrapper wrapper = new SynchronizedWrapper();

		wrapper.incrementTwice();

		assertThat(wrapper.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("블록 안에서는 모니터를 가지고 있고, 벗어나면 놓는다")
	void holdsLock() {
		SynchronizedWrapper wrapper = new SynchronizedWrapper();
		boolean[] heldInside = new boolean[1];

		wrapper.runWhileLocked(() -> heldInside[0] = wrapper.holdsLock());

		assertThat(heldInside[0]).isTrue();
		assertThat(wrapper.holdsLock()).isFalse();
	}

	@Test
	@DisplayName("모니터 진입을 기다리는 스레드는 BLOCKED 상태가 된다")
	void contenderIsBlocked() throws Exception {
		// @start region="blocked"
		SynchronizedWrapper wrapper = new SynchronizedWrapper();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Started<Void> holder = TestThreads.start("holder", () -> {
			wrapper.runWhileLocked(() -> {
				entered.countDown();
				TestThreads.awaitLatch(release);
			});
			return null;
		});
		entered.await();

		Started<Void> contender = TestThreads.start("contender", () -> {
			wrapper.increment();
			return null;
		});

		// ReentrantLock이었다면 WAITING이다. 모니터 진입 대기는 JVM이 BLOCKED로 따로 구분한다.
		TestThreads.awaitState(contender.thread(), Thread.State.BLOCKED);

		release.countDown();
		holder.join();
		contender.join();
		assertThat(wrapper.get()).isEqualTo(1);
		// @end
	}

}
