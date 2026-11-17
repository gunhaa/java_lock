package com.gunha.lock.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CasCounterTest {

	@Test
	@DisplayName("직접 쓴 CAS 루프는 실패하면 재시도하므로 값이 유실되지 않는다")
	void casLoopIsCorrect() throws InterruptedException {
		CasCounter counter = new CasCounter();

		TestThreads.runConcurrently(8, 100_000, counter::incrementWithCasLoop);

		assertThat(counter.get()).isEqualTo(8 * 100_000);
		// 실패 횟수는 코어 수와 스케줄링에 따라 달라서 단언하지 않고 출력만 한다.
		System.out.printf("CAS 실패 후 재시도: %,d회 (총 증가 %,d회)%n", counter.casFailures(), counter.get());
	}

	@Test
	@DisplayName("incrementAndGet(intrinsic)도 락 없이 정확하다")
	void intrinsicIsCorrect() throws InterruptedException {
		CasCounter counter = new CasCounter();

		TestThreads.runConcurrently(8, 100_000, counter::incrementIntrinsic);

		assertThat(counter.get()).isEqualTo(8 * 100_000);
		assertThat(counter.casFailures()).isZero();
	}

}
