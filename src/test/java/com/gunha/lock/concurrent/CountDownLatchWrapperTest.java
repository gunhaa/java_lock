package com.gunha.lock.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CountDownLatchWrapperTest {

	@Test
	@DisplayName("모든 작업이 끝나면 true이고, 작업이 쓴 결과가 호출자에게 보인다(happens-before)")
	void runsAllAndPublishesResults() throws InterruptedException {
		// @start region="latch"
		int[] results = new int[8];
		List<Runnable> tasks = new ArrayList<>();
		for (int i = 0; i < results.length; i++) {
			int index = i;
			tasks.add(() -> results[index] = index * index);
		}

		boolean completed = CountDownLatchWrapper.runAllAndAwait(tasks, 5, TimeUnit.SECONDS);

		assertThat(completed).isTrue();
		assertThat(results).containsExactly(0, 1, 4, 9, 16, 25, 36, 49);
		// @end
	}

	@Test
	@DisplayName("시간 안에 끝나지 않은 작업이 있으면 false")
	void timesOut() throws InterruptedException {
		CountDownLatch neverUntilCleanup = new CountDownLatch(1);
		List<Runnable> tasks = List.of(() -> {
		}, () -> TestThreads.awaitLatch(neverUntilCleanup));

		boolean completed = CountDownLatchWrapper.runAllAndAwait(tasks, 50, TimeUnit.MILLISECONDS);

		assertThat(completed).isFalse();
		neverUntilCleanup.countDown();
	}

	@Test
	@DisplayName("일회용이다: 0이 된 뒤 countDown해도 그대로 0이고, await는 바로 돌아온다")
	void oneShot() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(1);

		latch.countDown();
		latch.countDown();

		assertThat(latch.getCount()).isZero();
		assertThat(latch.await(0, TimeUnit.MILLISECONDS)).isTrue();
	}

}
