package com.gunha.lock.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class LongAdderWrapperTest {

	@Test
	@DisplayName("여러 셀에 나눠 더해도, 갱신이 멈춘 뒤의 합계는 정확하다")
	void sumIsExactAfterUpdatesStop() throws InterruptedException {
		// @start region="adder"
		LongAdderWrapper wrapper = new LongAdderWrapper();

		TestThreads.runConcurrently(8, 100_000, wrapper::increment);

		assertThat(wrapper.sum()).isEqualTo(8L * 100_000);
		// @end
	}

}
