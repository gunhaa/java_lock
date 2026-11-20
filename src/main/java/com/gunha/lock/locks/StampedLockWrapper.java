package com.gunha.lock.locks;

import java.util.concurrent.locks.StampedLock;

/**
 * {@link StampedLock}을 감싼 래퍼. 2차원 좌표 (x, y)를 보호하며, JDK 공식 문서의 {@code Point} 예제를 바탕으로 했다.
 * 메서드 이름 끝에 그 메서드가 쓰는 {@code StampedLock} 모드(쓰기 락, 낙관적 읽기)를 붙였다.
 *
 * <h2>stamp란</h2>
 * {@code StampedLock}의 {@code state}는 64비트 long이다. 쓰기 락 비트, 버전, 독자 수가 들어 있다.
 * 락을 얻으면 그 시점의 상태 값인 stamp를 돌려받고, 해제할 때 그 stamp를 넘긴다.
 * 쓰기 락을 얻고 풀 때마다 버전이 바뀌므로, stamp가 그대로인지만 보면 "그사이 누가 썼는가"를 알 수 있다.
 *
 * <h2>낙관적 읽기 ({@code StampedLock.tryOptimisticRead})</h2>
 * <ol>
 * <li>{@code tryOptimisticRead()}: 쓰기 락이 없으면 현재 stamp를 돌려준다. <b>공유 메모리에 아무것도 쓰지 않는다.</b>
 *     CAS도 없다. 쓰기 락이 걸려 있으면 0을 돌려주고, 0은 {@code validate}에서 항상 실패한다.</li>
 * <li>필드를 지역 변수로 복사한다. 이 순간 다른 스레드가 쓰는 중일 수 있어 값이 서로 안 맞을 수 있다.</li>
 * <li>{@code validate(stamp)}: 메모리 로드 배리어({@code VarHandle.acquireFence})를 친 뒤 stamp가 그대로인지 본다.
 *     그대로면 복사한 값은 일관된 스냅샷이다.</li>
 * <li>실패하면 진짜 읽기 락으로 다시 읽는다.</li>
 * </ol>
 * CPU 관점: 읽기 락({@link ReentrantReadWriteLockWrapper})은 독자마다 {@code state}에 CAS로 써서 캐시 라인을 독점(Modified)하려 한다.
 * 낙관적 읽기는 읽기만 하므로 여러 코어가 캐시 라인을 공유(Shared) 상태로 함께 가질 수 있다.
 * 쓰기가 드물고 읽기가 짧고 잦을 때 가장 빠르다.
 *
 * <h2>대가와 주의</h2>
 * <ul>
 * <li><b>재진입 불가</b>: 쓰기 락을 쥔 스레드가 다시 {@code writeLock()}하면 자기 자신을 기다리며 교착된다.</li>
 * <li><b>소유자 개념 없음</b>: stamp만 있으면 다른 스레드가 해제할 수 있다.</li>
 * <li>{@code Condition}이 없다.</li>
 * <li>{@code readLock()}/{@code writeLock()}은 인터럽트해도 포기하지 않는다. 포기할 수 있어야 하면
 *     {@code readLockInterruptibly()}/{@code writeLockInterruptibly()}를 쓴다.</li>
 * <li>검증 전에는 읽은 값을 믿으면 안 된다. 특히 읽은 참조를 따라가거나, 그 값으로 예외가 날 수 있는 계산을 하면 안 된다.</li>
 * </ul>
 *
 * <h2>테스트로 확인</h2>
 * 쓰기가 계속 끼어들어도 낙관적 읽기는 {@code validate}로 걸러 내므로, 섞인 좌표가 아니라 일관된 좌표만 본다.
 * {@snippet file="com/gunha/lock/locks/StampedLockWrapperTest.java" region="optimistic"}
 */
public class StampedLockWrapper {

	private final StampedLock sl = new StampedLock();

	private double x;

	private double y;

	/** {@link StampedLock#writeLock()}을 얻고 좌표를 옮긴다. */
	public void moveWithWriteLock(double deltaX, double deltaY) {
		long stamp = sl.writeLock();
		try {
			x += deltaX;
			y += deltaY;
		}
		finally {
			sl.unlockWrite(stamp);
		}
	}

	/** {@link StampedLock#tryOptimisticRead()}로 원점까지의 거리를 읽는다. {@code validate}에 실패하면 읽기 락으로 다시 읽는다. */
	public double distanceWithOptimisticRead() {
		long stamp = sl.tryOptimisticRead();
		double currentX = x;
		double currentY = y;
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

}
