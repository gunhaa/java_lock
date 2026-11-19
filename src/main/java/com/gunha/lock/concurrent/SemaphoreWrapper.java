package com.gunha.lock.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * {@link Semaphore}를 감싼 래퍼. 허가(permit) 수로 동시에 실행되는 작업 수를 제한한다. (예: 외부 API 동시 호출 수, 커넥션 수 제한)
 *
 * <h2>동작 (AQS 공유 모드)</h2>
 * <ul>
 * <li>{@code state} = 남은 허가 수.</li>
 * <li>{@code acquire()}: CAS로 {@code state}를 1 줄인다. 0이면 sync queue에서 park, 상태는 {@code WAITING}.</li>
 * <li>{@code release()}: CAS로 1 늘리고 대기자를 깨운다. 공유 모드라 허가가 여러 개 생기면 여러 대기자가 연달아 깨어난다.</li>
 * <li>공정성 옵션은 {@code ReentrantLock}과 같다({@code new Semaphore(n, true)}).</li>
 * </ul>
 *
 * <h2>락과 다른 점: 소유자가 없다</h2>
 * 세마포어는 "누가 허가를 가져갔는지" 기록하지 않고 숫자만 센다.
 * <ul>
 * <li>얻은 스레드가 아닌 다른 스레드가 {@code release()}해도 된다(신호 용도로 쓸 수 있다).</li>
 * <li>얻지 않고 {@code release()}하면 허가가 처음보다 늘어난다. 예외가 나지 않으므로 버그가 조용히 숨는다.
 *     그래서 {@link #callWithPermit}처럼 반드시 {@code acquire} 성공 뒤의 {@code finally}에서만 해제한다.</li>
 * <li>재진입 개념도 없다. 허가 1개짜리(binary semaphore)에서 같은 스레드가 두 번 얻으려 하면 자기 자신을 기다린다.</li>
 * </ul>
 *
 * <h2>테스트로 확인</h2>
 * 허가 3개가 모두 쓰이면 네 번째 호출은 {@code WAITING}으로 기다린다.
 * {@snippet file="com/gunha/lock/concurrent/SemaphoreWrapperTest.java" region="limit"}
 */
public class SemaphoreWrapper {

	private final Semaphore semaphore;

	/**
	 * 허가 수를 정해 세마포어를 만든다.
	 *
	 * @param permits 처음 허가 수. 동시에 실행될 수 있는 작업 수다.
	 */
	public SemaphoreWrapper(int permits) {
		this.semaphore = new Semaphore(permits);
	}

	/** {@code acquire()}로 허가를 얻은 뒤 task를 실행하고, 끝나면(예외가 나도) {@code release()}로 허가를 돌려준다. */
	public <T> T callWithPermit(Callable<T> task) throws Exception {
		semaphore.acquire();
		try {
			return task.call();
		}
		finally {
			semaphore.release();
		}
	}

	/** {@link Semaphore#availablePermits()}에 위임한다. */
	public int availablePermits() {
		return semaphore.availablePermits();
	}

}
