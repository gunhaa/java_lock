package com.gunha.lock.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gunha.lock.support.TestThreads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class UnsafeCounterTest {

	@Test
	@DisplayName("volatile만으로는 count++가 원자적이지 않아 갱신이 유실된다")
	void volatileDoesNotMakeIncrementAtomic() throws InterruptedException {
		// @start region="lost-update"
		// 코어가 하나면 스레드가 번갈아 실행될 뿐 동시에 겹치지 않아 유실이 거의 안 생긴다.
		assumeTrue(Runtime.getRuntime().availableProcessors() > 1, "멀티 코어에서만 의미 있는 실험");
		UnsafeCounter counter = new UnsafeCounter();

		TestThreads.runConcurrently(8, 100_000, counter::increment);

		assertThat(counter.get()).isLessThan(8 * 100_000);
		// @end
	}

}
