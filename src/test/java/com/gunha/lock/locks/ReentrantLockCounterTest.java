package com.gunha.lock.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class ReentrantLockCounterTest {

	@ParameterizedTest(name = "fair={0}")
	@ValueSource(booleans = { false, true })
	@DisplayName("공정·비공정 모두 동시 증가에도 값이 유실되지 않는다")
	void mutualExclusion(boolean fair) throws InterruptedException {
		ReentrantLockCounter counter = new ReentrantLockCounter(fair);

		// 공정 락은 넘겨줄 때마다 컨텍스트 스위치가 일어나 느리므로 횟수를 작게 잡는다.
		TestThreads.runConcurrently(4, 10_000, counter::increment);

		assertThat(counter.isFair()).isEqualTo(fair);
		assertThat(counter.get()).isEqualTo(4 * 10_000);
	}

	@Test
	@DisplayName("재진입하면 보유 횟수(state)가 늘어난다")
	void reentrantHoldCount() {
		ReentrantLockCounter counter = new ReentrantLockCounter(false);

		assertThat(counter.nestedHoldCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("락을 기다리는 스레드는 BLOCKED가 아니라 WAITING이다 (AQS가 park로 재우므로)")
	void contenderIsWaitingNotBlocked() throws Exception {
		// @start region="waiting"
		ReentrantLockCounter counter = new ReentrantLockCounter(false);
		CountDownLatch release = new CountDownLatch(1);
		Started<Void> holder = holdLock(counter, release);

		Started<Void> contender = TestThreads.start("contender", () -> {
			counter.increment();
			return null;
		});

		TestThreads.awaitState(contender.thread(), Thread.State.WAITING);
		release.countDown();
		holder.join();
		contender.join();
		assertThat(counter.get()).isEqualTo(1);
		// @end
	}

	@Test
	@DisplayName("tryLock(timeout)은 시간 안에 못 얻으면 포기하고 false를 돌려준다")
	void tryLockGivesUp() throws Exception {
		ReentrantLockCounter counter = new ReentrantLockCounter(false);
		CountDownLatch release = new CountDownLatch(1);
		Started<Void> holder = holdLock(counter, release);

		assertThat(counter.tryIncrement(50, TimeUnit.MILLISECONDS)).isFalse();

		release.countDown();
		holder.join();
		assertThat(counter.get()).isZero();
	}

	@Test
	@DisplayName("tryLock(timeout)으로 기다리는 동안은 TIMED_WAITING이고, 그 안에 풀리면 얻는다")
	void tryLockSucceedsWhenReleasedInTime() throws Exception {
		ReentrantLockCounter counter = new ReentrantLockCounter(false);
		CountDownLatch release = new CountDownLatch(1);
		Started<Void> holder = holdLock(counter, release);

		Started<Boolean> contender = TestThreads.start("contender",
				() -> counter.tryIncrement(5, TimeUnit.SECONDS));
		TestThreads.awaitState(contender.thread(), Thread.State.TIMED_WAITING);
		release.countDown();

		assertThat(contender.join()).isTrue();
		holder.join();
		assertThat(counter.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("lockInterruptibly로 기다리는 스레드는 인터럽트로 깨워 포기시킬 수 있다")
	void lockInterruptibly() throws Exception {
		// @start region="interruptibly"
		ReentrantLockCounter counter = new ReentrantLockCounter(false);
		CountDownLatch release = new CountDownLatch(1);
		Started<Void> holder = holdLock(counter, release);

		Started<Void> contender = TestThreads.start("contender", () -> {
			counter.incrementInterruptibly();
			return null;
		});
		TestThreads.awaitState(contender.thread(), Thread.State.WAITING);
		contender.thread().interrupt();

		assertThatThrownBy(contender::join).isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(InterruptedException.class);
		release.countDown();
		holder.join();
		assertThat(counter.get()).isZero();
		// @end
	}

	@Test
	@DisplayName("소유자가 아닌 스레드가 unlock하면 IllegalMonitorStateException")
	void unlockByNonOwner() {
		ReentrantLock lock = new ReentrantLock();

		assertThatThrownBy(lock::unlock).isInstanceOf(IllegalMonitorStateException.class);
	}

	/** 다른 스레드가 락을 쥐고 release가 열릴 때까지 놓지 않게 한다. */
	private static Started<Void> holdLock(ReentrantLockCounter counter, CountDownLatch release)
			throws InterruptedException {
		CountDownLatch entered = new CountDownLatch(1);
		Started<Void> holder = TestThreads.start("holder", () -> {
			counter.runWhileLocked(() -> {
				entered.countDown();
				TestThreads.awaitLatch(release);
			});
			return null;
		});
		entered.await();
		return holder;
	}

}
