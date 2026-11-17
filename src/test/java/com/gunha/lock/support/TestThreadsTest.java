package com.gunha.lock.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TestThreadsTest {

	@Test
	@DisplayName("runConcurrently는 모든 작업이 끝난 뒤에 돌아온다")
	void waitsForAllTasks() throws InterruptedException {
		AtomicInteger count = new AtomicInteger();

		TestThreads.runConcurrently(4, 1_000, count::incrementAndGet);

		assertThat(count.get()).isEqualTo(4 * 1_000);
	}

	@Test
	@DisplayName("작업에서 난 예외를 삼키지 않고 AssertionError로 알린다")
	void propagatesTaskException() {
		assertThatThrownBy(() -> TestThreads.runConcurrently(2, 1, () -> {
			throw new IllegalStateException("작업 실패");
		})).isInstanceOf(AssertionError.class).hasCauseInstanceOf(IllegalStateException.class);
	}

}
