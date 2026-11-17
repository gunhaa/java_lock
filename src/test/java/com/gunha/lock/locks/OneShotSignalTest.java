package com.gunha.lock.locks;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class OneShotSignalTest {

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
	@DisplayName("await 중인 스레드는 WAITING이고, signal하면 깨어난다")
	void awaitThenSignal() throws Exception {
		OneShotSignal signal = new OneShotSignal();
		Started<Void> waiter = TestThreads.start("waiter", () -> {
			signal.await();
			return null;
		});

		TestThreads.awaitState(waiter.thread(), Thread.State.WAITING);
		signal.signal();

		waiter.join();
	}

	@Test
	@DisplayName("signal이 먼저 오면 await는 기다리지 않는다")
	void signalBeforeAwait() {
		OneShotSignal signal = new OneShotSignal();

		signal.signal();

		signal.await();
	}

}
