package com.gunha.lock.locks;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import java.util.concurrent.locks.StampedLock;
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
	@DisplayName("낙관적 읽기 도중 쓰기가 끼어들면 validate가 실패해 읽기 락으로 다시 읽는다")
	void optimisticReadFallsBackWhenWritten() {
		// @start region="optimistic"
		StampedLockWrapper wrapper = new StampedLockWrapper();

		// 낙관적으로 (0, 0)을 읽은 직후 (3, 4)로 이동시킨다.
		// 검증 없이 그대로 썼다면 0.0, 검증에 실패해 다시 읽었으므로 5.0이다.
		double distance = wrapper.distanceWithOptimisticRead(() -> wrapper.moveWithWriteLock(3, 4));

		assertThat(distance).isEqualTo(5.0);
		// @end
	}

	@Test
	@DisplayName("쓰기 락을 얻고 풀면 이전 낙관적 stamp는 무효가 된다")
	void writeInvalidatesStamp() {
		StampedLock lock = new StampedLock();
		long optimistic = lock.tryOptimisticRead();
		assertThat(lock.validate(optimistic)).isTrue();

		lock.unlockWrite(lock.writeLock());

		assertThat(lock.validate(optimistic)).isFalse();
	}

	@Test
	@DisplayName("재진입이 안 된다: 쓰기 락을 쥔 스레드도 다시 얻지 못한다")
	void notReentrant() {
		StampedLock lock = new StampedLock();
		long stamp = lock.writeLock();

		// tryXxx는 실패하면 0을 돌려준다. writeLock()이었다면 자기 자신을 기다리며 교착된다.
		assertThat(lock.tryWriteLock()).isZero();
		assertThat(lock.tryReadLock()).isZero();

		lock.unlockWrite(stamp);
	}

	@Test
	@DisplayName("소유자 개념이 없어서 stamp만 있으면 다른 스레드가 해제할 수 있다")
	void noOwnership() throws Exception {
		StampedLock lock = new StampedLock();
		long stamp = lock.writeLock();

		TestThreads.start("other", () -> {
			lock.unlockWrite(stamp);
			return null;
		}).join();

		assertThat(lock.isWriteLocked()).isFalse();
	}

	@Test
	@DisplayName("tryConvertToWriteLock으로 읽기 락을 쓰기 락으로 바꿔 원점일 때만 이동한다")
	void moveIfAtOriginWithConvertToWriteLock() {
		StampedLockWrapper wrapper = new StampedLockWrapper();

		assertThat(wrapper.moveIfAtOriginWithConvertToWriteLock(3, 4)).isTrue();
		assertThat(wrapper.moveIfAtOriginWithConvertToWriteLock(6, 8)).isFalse();
		assertThat(wrapper.distanceWithOptimisticRead()).isEqualTo(5.0);
	}

}
