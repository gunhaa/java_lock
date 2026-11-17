package com.gunha.lock.locks;

import java.util.concurrent.locks.StampedLock;

/**
 * {@link StampedLock}으로 보호한 2차원 좌표. JDK 공식 문서의 예제를 바탕으로 했다.
 *
 * <h2>stamp란</h2>
 * {@code StampedLock}의 {@code state}는 64비트 long이다. 쓰기 락 비트, 버전, 독자 수가 들어 있다.
 * 락을 얻으면 그 시점의 상태 값인 stamp를 돌려받고, 해제할 때 그 stamp를 넘긴다.
 * 쓰기 락을 얻고 풀 때마다 버전이 바뀌므로, stamp가 그대로인지만 보면 "그사이 누가 썼는가"를 알 수 있다.
 *
 * <h2>낙관적 읽기 ({@link #distanceFromOrigin()})</h2>
 * <ol>
 * <li>{@code tryOptimisticRead()}: 쓰기 락이 없으면 현재 stamp를 돌려준다. <b>공유 메모리에 아무것도 쓰지 않는다.</b>
 *     CAS도 없다.</li>
 * <li>필드를 지역 변수로 복사한다. 이 순간 다른 스레드가 쓰는 중일 수 있어 값이 서로 안 맞을 수 있다.</li>
 * <li>{@code validate(stamp)}: 메모리 로드 배리어({@code VarHandle.acquireFence})를 친 뒤 stamp가 그대로인지 본다.
 *     그대로면 복사한 값은 일관된 스냅샷이다.</li>
 * <li>실패하면 진짜 읽기 락으로 다시 읽는다.</li>
 * </ol>
 * CPU 관점: 읽기 락({@link ReadWriteCache})은 독자마다 {@code state}에 CAS로 써서 캐시 라인을 독점(Modified)하려 한다.
 * 낙관적 읽기는 읽기만 하므로 여러 코어가 캐시 라인을 공유(Shared) 상태로 함께 가질 수 있다.
 * 쓰기가 드물고 읽기가 짧고 잦을 때 가장 빠르다.
 *
 * <h2>대가와 주의</h2>
 * <ul>
 * <li><b>재진입 불가</b>: 쓰기 락을 쥔 스레드가 다시 {@code writeLock()}하면 자기 자신을 기다리며 교착된다.</li>
 * <li><b>소유자 개념 없음</b>: stamp만 있으면 다른 스레드가 해제할 수 있다.</li>
 * <li>{@code Condition}이 없다. {@code readLock()}/{@code writeLock()}은 인터럽트로 깨울 수 없다(Interruptibly 버전 사용).</li>
 * <li>검증 전에는 읽은 값을 믿으면 안 된다. 특히 읽은 참조를 따라가거나, 그 값으로 예외가 날 수 있는 계산을 하면 안 된다.</li>
 * </ul>
 */
public class StampedPoint {

	private final StampedLock sl = new StampedLock();

	private double x;

	private double y;

	public void move(double deltaX, double deltaY) {
		long stamp = sl.writeLock();
		try {
			x += deltaX;
			y += deltaY;
		}
		finally {
			sl.unlockWrite(stamp);
		}
	}

	public double distanceFromOrigin() {
		return distanceFromOrigin(() -> {
		});
	}

	/** 낙관적으로 읽은 직후 afterOptimisticRead를 실행한다. 테스트에서 그사이 쓰기를 끼워 넣는 데 쓴다. */
	double distanceFromOrigin(Runnable afterOptimisticRead) {
		long stamp = sl.tryOptimisticRead();
		double currentX = x;
		double currentY = y;
		afterOptimisticRead.run();
		if (!sl.validate(stamp)) {
			stamp = sl.readLock();
			try {
				currentX = x;
				currentY = y;
			}
			finally {
				sl.unlockRead(stamp);
			}
		}
		return Math.hypot(currentX, currentY);
	}

	/** 원점에 있을 때만 이동한다. 읽기 락을 쓰기 락으로 바꾸는 {@code tryConvertToWriteLock}을 보여 준다. */
	public boolean moveIfAtOrigin(double newX, double newY) {
		long stamp = sl.readLock();
		try {
			while (x == 0.0 && y == 0.0) {
				// 다른 독자가 없으면 그 자리에서 쓰기 락으로 바뀐다(ReentrantReadWriteLock에는 없는 기능).
				long writeStamp = sl.tryConvertToWriteLock(stamp);
				if (writeStamp != 0L) {
					stamp = writeStamp;
					x = newX;
					y = newY;
					return true;
				}
				// 바꾸지 못하면 읽기 락을 놓고 쓰기 락을 새로 얻은 뒤 조건을 다시 확인한다.
				sl.unlockRead(stamp);
				stamp = sl.writeLock();
			}
			return false;
		}
		finally {
			sl.unlock(stamp);
		}
	}

}
