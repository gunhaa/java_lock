package com.gunha.lock.locks;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class StampedLockWrapperTest {

	@Test
	@DisplayName("쓰기가 끼어들지 않으면 낙관적 읽기만으로 값을 얻는다")
	void optimisticReadWithoutWriter() {
		StampedLockWrapper wrapper = new StampedLockWrapper();
		wrapper.moveWithWriteLock(3, 4);

		assertThat(wrapper.distanceWithOptimisticRead()).isEqualTo(5.0);
	}

	@Test
	@DisplayName("쓰기가 계속 끼어들어도 낙관적 읽기는 섞인 좌표를 돌려주지 않는다")
	void optimisticReadSeesConsistentSnapshot() throws Exception {
		// @start region="optimistic"
		StampedLockWrapper wrapper = new StampedLockWrapper();
		AtomicBoolean stop = new AtomicBoolean();
		// (0, 0)과 (3, 4) 사이를 계속 오간다. 거리는 언제나 0 또는 5다.
		Started<Void> writer = TestThreads.start("writer", () -> {
			while (!stop.get()) {
				wrapper.moveWithWriteLock(3, 4);
				wrapper.moveWithWriteLock(-3, -4);
			}
			return null;
		});

		// validate 없이 읽었다면 x만 바뀐 (3, 0)을 읽어 3.0 같은 값이 나올 수 있다.
		for (int i = 0; i < 100_000; i++) {
			assertThat(wrapper.distanceWithOptimisticRead()).isIn(0.0, 5.0);
		}

		stop.set(true);
		writer.join();
		// @end
	}

}
