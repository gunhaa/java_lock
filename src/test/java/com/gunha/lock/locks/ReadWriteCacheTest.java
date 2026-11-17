package com.gunha.lock.locks;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ReadWriteCacheTest {

	@Test
	@DisplayName("읽기 락은 여러 스레드가 동시에 가진다")
	void readersShare() throws Exception {
		ReadWriteCache<String, String> cache = new ReadWriteCache<>();
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		List<Started<Void>> readers = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			readers.add(TestThreads.start("reader-" + i, () -> {
				cache.readWhile(() -> {
					entered.countDown();
					TestThreads.awaitLatch(release);
				});
				return null;
			}));
		}

		entered.await();
		assertThat(cache.readLockCount()).isEqualTo(2);

		release.countDown();
		for (Started<Void> reader : readers) {
			reader.join();
		}
		assertThat(cache.readLockCount()).isZero();
	}

	@Test
	@DisplayName("읽기 락이 남아 있으면 쓰기는 WAITING으로 기다린다")
	void writerWaitsForReaders() throws Exception {
		ReadWriteCache<String, String> cache = new ReadWriteCache<>();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Started<Void> reader = TestThreads.start("reader", () -> {
			cache.readWhile(() -> {
				entered.countDown();
				TestThreads.awaitLatch(release);
			});
			return null;
		});
		entered.await();

		Started<Void> writer = TestThreads.start("writer", () -> {
			cache.put("k", "v");
			return null;
		});

		TestThreads.awaitState(writer.thread(), Thread.State.WAITING);
		assertThat(cache.isWriteLocked()).isFalse();

		release.countDown();
		reader.join();
		writer.join();
		assertThat(cache.get("k")).isEqualTo("v");
	}

	@Test
	@DisplayName("getOrLoad는 여러 스레드가 동시에 불러도 한 번만 로드한다")
	void loadsOnce() throws InterruptedException {
		ReadWriteCache<String, Integer> cache = new ReadWriteCache<>();
		AtomicInteger loads = new AtomicInteger();

		TestThreads.runConcurrently(8, 1_000, () -> cache.getOrLoad("k", key -> loads.incrementAndGet()));

		assertThat(loads.get()).isEqualTo(1);
		assertThat(cache.get("k")).isEqualTo(1);
	}

	@Test
	@DisplayName("쓰기 → 읽기 다운그레이드는 되지만, 읽기 → 쓰기 업그레이드는 안 된다")
	void downgradeYesUpgradeNo() {
		// @start region="downgrade"
		ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

		// 업그레이드: 읽기 락을 쥔 채로는 쓰기 락을 얻지 못한다. lock()이었다면 영원히 기다린다.
		rwLock.readLock().lock();
		assertThat(rwLock.writeLock().tryLock()).isFalse();
		rwLock.readLock().unlock();

		// 다운그레이드: 쓰기 락을 쥔 채 읽기 락을 얻고, 쓰기 락만 놓는다.
		rwLock.writeLock().lock();
		rwLock.readLock().lock();
		rwLock.writeLock().unlock();
		assertThat(rwLock.isWriteLocked()).isFalse();
		assertThat(rwLock.getReadLockCount()).isEqualTo(1);
		rwLock.readLock().unlock();
		// @end
	}

}
