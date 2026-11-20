package com.gunha.lock.locks;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ReentrantReadWriteLockWrapperTest {

	@Test
	@DisplayName("읽기 락은 여러 스레드가 동시에 가진다")
	void readersShare() throws Exception {
		ReentrantReadWriteLockWrapper<String, String> wrapper = new ReentrantReadWriteLockWrapper<>();
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		List<Started<Void>> readers = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			readers.add(TestThreads.start("reader-" + i, () -> {
				wrapper.runWhileReadLocked(() -> {
					entered.countDown();
					TestThreads.awaitLatch(release);
				});
				return null;
			}));
		}

		// 두 독자가 모두 읽기 락 안에 들어와 있다.
		entered.await();
		assertThat(wrapper.getReadLockCount()).isEqualTo(2);

		release.countDown();
		for (Started<Void> reader : readers) {
			reader.join();
		}
		assertThat(wrapper.getReadLockCount()).isZero();
	}

	@Test
	@DisplayName("읽기 락이 남아 있으면 쓰기는 WAITING으로 기다린다")
	void writerWaitsForReaders() throws Exception {
		// @start region="writer-waits"
		ReentrantReadWriteLockWrapper<String, String> wrapper = new ReentrantReadWriteLockWrapper<>();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Started<Void> reader = TestThreads.start("reader", () -> {
			wrapper.runWhileReadLocked(() -> {
				entered.countDown();
				TestThreads.awaitLatch(release);
			});
			return null;
		});
		entered.await();

		Started<Void> writer = TestThreads.start("writer", () -> {
			wrapper.put("k", "v");
			return null;
		});

		// 쓰기 락은 배타라서 독자가 나갈 때까지 sync queue에서 기다린다.
		TestThreads.awaitState(writer.thread(), Thread.State.WAITING);

		release.countDown();
		reader.join();
		writer.join();
		assertThat(wrapper.get("k")).isEqualTo("v");
		// @end
	}

}
