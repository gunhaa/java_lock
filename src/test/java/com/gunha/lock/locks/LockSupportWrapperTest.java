package com.gunha.lock.locks;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class LockSupportWrapperTest {

	@Test
	@DisplayName("unpark가 park보다 먼저 와도 permit이 남아 있어 park가 바로 돌아온다")
	void unparkBeforePark() {
		// @start region="permit"
		LockSupport.unpark(Thread.currentThread());

		// permit이 없었다면 여기서 영원히 잠들어 @Timeout으로 실패한다.
		LockSupport.park();
		// @end
	}

	@Test
	@DisplayName("park 중인 스레드는 WAITING이고, unpark하면 깨어난다")
	void parkThenUnpark() throws Exception {
		LockSupportWrapper wrapper = new LockSupportWrapper();
		Started<Void> waiter = TestThreads.start("waiter", () -> {
			wrapper.park();
			return null;
		});

		TestThreads.awaitState(waiter.thread(), Thread.State.WAITING);
		wrapper.unpark();

		waiter.join();
	}

	@Test
	@DisplayName("인터럽트하면 park는 예외 없이 돌아오고, 인터럽트 플래그는 켜진 채 남는다")
	void interruptReturnsWithFlagSet() throws Exception {
		LockSupportWrapper wrapper = new LockSupportWrapper();
		Started<Boolean> waiter = TestThreads.start("waiter", () -> {
			wrapper.park();
			return Thread.currentThread().isInterrupted();
		});
		TestThreads.awaitState(waiter.thread(), Thread.State.WAITING);

		waiter.thread().interrupt();

		// 루프 조건에 플래그가 없다면 park가 계속 바로 돌아와 끝나지 않고, join()이 시간 초과로 실패한다.
		assertThat(waiter.join()).isTrue();
	}

	@Test
	@DisplayName("unpark가 먼저 오면 park는 기다리지 않는다")
	void parkReturnsWhenUnparkedFirst() {
		LockSupportWrapper wrapper = new LockSupportWrapper();

		wrapper.unpark();

		wrapper.park();
	}

}
