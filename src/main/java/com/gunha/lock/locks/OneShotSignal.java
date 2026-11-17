package com.gunha.lock.locks;

import java.util.concurrent.locks.LockSupport;

/**
 * {@link LockSupport#park}/{@link LockSupport#unpark}만으로 만든 일회용 신호. 대기자는 한 스레드라고 가정한다.
 * AQS와 이 패키지의 모든 락이 스레드를 재우고 깨울 때 결국 이 두 메서드를 쓴다.
 *
 * <h2>permit 모델</h2>
 * 스레드마다 permit이 0개 또는 1개 있다(세마포어처럼 쌓이지 않는다).
 * <ul>
 * <li>{@code unpark(t)}: t의 permit을 1로 만든다. 이미 1이면 그대로다.</li>
 * <li>{@code park()}: permit이 있으면 소비하고 즉시 돌아온다. 없으면 잠든다.</li>
 * </ul>
 * 그래서 <b>unpark가 park보다 먼저 와도 신호가 유실되지 않는다.</b> {@code Object.notify()}는 대기자가 없으면
 * 사라지는 것과 대조적이다. 이 성질 덕분에 AQS가 "큐에 넣기"와 "잠들기" 사이의 경쟁을 안전하게 처리한다.
 *
 * <h2>JVM·OS 관점</h2>
 * {@code LockSupport.park} → {@code Unsafe.park} → 스레드마다 하나 있는 HotSpot {@code Parker}.
 * Parker는 OS 뮤텍스·조건 변수와 {@code _counter}(= permit)를 가진다.
 * Linux는 {@code pthread_cond_wait}(futex 시스템 콜), Windows는 이벤트에 {@code WaitForSingleObject}.
 * 플랫폼 스레드는 OS 스케줄러의 실행 큐에서 빠지고, 상태는 {@code WAITING}이 된다.
 * 가상 스레드라면 OS 스레드를 재우지 않고, 컨티뉴에이션을 힙에 저장한 뒤 캐리어 스레드에서 내려온다(unmount).
 *
 * <h2>주의</h2>
 * <ul>
 * <li>{@code park}는 이유 없이 돌아올 수 있다(spurious). 인터럽트를 받아도 돌아오지만 예외는 던지지 않는다.
 *     그래서 반드시 조건 변수({@code signaled})와 함께 루프로 쓴다.</li>
 * <li>{@code park(this)}처럼 blocker 객체를 넘기면 스레드 덤프에 "무엇을 기다리는지"가 찍힌다.</li>
 * </ul>
 */
public class OneShotSignal {

	private volatile boolean signaled;

	private volatile Thread waiter;

	/** signal()이 올 때까지 기다린다. 이미 신호가 왔으면 바로 돌아온다. */
	public void await() {
		waiter = Thread.currentThread();
		while (!signaled) {
			LockSupport.park(this);
		}
	}

	public void signal() {
		// 순서가 중요하다: 플래그를 먼저 세워야, waiter를 못 본 경우에도 대기자가 루프 조건에서 신호를 본다.
		signaled = true;
		Thread current = waiter;
		if (current != null) {
			LockSupport.unpark(current);
		}
	}

}
