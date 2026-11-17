package com.gunha.lock.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class SemaphoreLimiterTest {

	@Test
	@DisplayName("허가 수만큼만 동시에 실행되고, 넘친 호출은 WAITING으로 기다린다")
	void limitsConcurrency() throws Exception {
		// @start region="limit"
		SemaphoreLimiter limiter = new SemaphoreLimiter(3);
		CountDownLatch entered = new CountDownLatch(3);
		CountDownLatch release = new CountDownLatch(1);
		List<Started<String>> running = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			running.add(TestThreads.start("running-" + i, () -> limiter.call(() -> {
				entered.countDown();
				TestThreads.awaitLatch(release);
				return "done";
			})));
		}
		entered.await();

		Started<String> fourth = TestThreads.start("fourth", () -> limiter.call(() -> "fourth"));

		TestThreads.awaitState(fourth.thread(), Thread.State.WAITING);
		assertThat(limiter.availablePermits()).isZero();

		release.countDown();
		for (Started<String> task : running) {
			assertThat(task.join()).isEqualTo("done");
		}
		assertThat(fourth.join()).isEqualTo("fourth");
		assertThat(limiter.availablePermits()).isEqualTo(3);
		// @end
	}

	@Test
	@DisplayName("작업이 예외를 던져도 허가는 돌려받는다")
	void releasesOnException() {
		SemaphoreLimiter limiter = new SemaphoreLimiter(1);

		try {
			limiter.call(() -> {
				throw new IllegalStateException("실패");
			});
		}
		catch (Exception ignored) {
			// 예외 자체는 관심 없다.
		}

		assertThat(limiter.availablePermits()).isEqualTo(1);
	}

	@Test
	@DisplayName("소유자가 없어서 다른 스레드가 release해도 되고, 얻지 않고 release하면 허가가 늘어난다")
	void noOwnership() throws Exception {
		Semaphore signal = new Semaphore(0);
		TestThreads.start("releaser", () -> {
			signal.release();
			return null;
		});
		signal.acquire();

		Semaphore semaphore = new Semaphore(1);
		semaphore.release();
		assertThat(semaphore.availablePermits()).isEqualTo(2);
	}

}
