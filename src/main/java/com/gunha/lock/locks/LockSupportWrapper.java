package com.gunha.lock.locks;

import java.util.concurrent.locks.LockSupport;

/**
 * {@link LockSupport}를 감싼 래퍼. {@link LockSupport#park}/{@link LockSupport#unpark}만으로 일회용 신호를 만든다.
 * AQS와 {@code java.util.concurrent.locks}의 모든 락이 스레드를 재우고 깨울 때 결국 이 두 메서드를 쓴다.
 * 패키지 설명의 계층 그림에서 자바 코드로는 가장 아래층이다. 위층의 도구들은 여기에 {@code state} CAS, sync queue, 락 연동을 얹은 것이고,
 * 이 클래스는 그것들 없이 park/unpark를 직접 쓴다. 큐가 없으므로 대기자는 한 스레드라고 가정한다.
 *
 * <h2>permit 모델</h2>
 * 스레드마다 permit이 0개 또는 1개 있다(세마포어처럼 쌓이지 않는다).
 * <ul>
 * <li>{@code unpark(t)}: t의 permit을 1로 만든다. 이미 1이면 그대로다.</li>
 * <li>{@code park()}: permit이 있으면 소비하고 즉시 돌아온다. 없으면 잠든다.</li>
 * </ul>
 * 그래서 <b>unpark가 park보다 먼저 와도 신호가 유실되지 않는다.</b> {@code Object.notify()}는 대기자가 없으면
 * 사라지는 것과 대조적이다. 이 성질 덕분에 AQS는 대기자가 큐에 들어간 뒤 park하기 직전에 해제하는 쪽이 unpark해도 신호를 잃지 않는다.
 *
 * <h2>JVM·OS 관점</h2>
 * {@code LockSupport.park} → {@code Unsafe.park} → 스레드마다 하나 있는 HotSpot {@code Parker}.
 * Parker는 {@code _counter}(= permit)와 OS 대기 수단을 가진다. Linux는 pthread 뮤텍스·조건 변수로
 * {@code pthread_cond_wait}(futex 시스템 콜)를 쓰고, Windows는 이벤트 객체에 {@code WaitForSingleObject}를 쓴다(docs/03 3.7절).
 * 플랫폼 스레드는 OS 스케줄러의 실행 큐에서 빠지고, 상태는 {@code WAITING}이 된다.
 * 가상 스레드라면 OS 스레드를 재우지 않고, 컨티뉴에이션을 힙에 저장한 뒤 캐리어 스레드에서 내려온다(unmount).
 *
 * <h2>주의</h2>
 * <ul>
 * <li>{@code park}는 이유 없이 돌아올 수 있다(spurious). 그래서 반드시 상태 플래그({@code signaled})를 루프로 다시 확인한다.</li>
 * <li>{@code park}는 인터럽트를 받으면 예외 없이 돌아오고, 인터럽트 플래그도 지우지 않는다.
 *     플래그가 켜진 채 다시 {@code park}하면 바로 돌아오므로, 루프 조건에 플래그를 넣지 않으면 잠들지 못하고 CPU를 계속 쓴다(busy spin).
 *     인터럽트를 받아도 계속 기다리기({@code lock()})나 예외로 끝내기({@code lockInterruptibly()})는 이 위에 얹는 정책이다.</li>
 * <li>{@code park(this)}처럼 blocker 객체를 넘기면 스레드 덤프에 "무엇을 기다리는지"가 찍힌다.</li>
 * </ul>
 *
 * <h2>테스트로 확인</h2>
 * {@code unpark}가 먼저 와도 permit이 남아 있어 {@code park}가 바로 돌아온다.
 * {@snippet file="com/gunha/lock/locks/LockSupportWrapperTest.java" region="permit"}
 */
public class LockSupportWrapper {

	private volatile boolean signaled;

	private volatile Thread waiter;

	/**
	 * {@link #unpark()}가 불리거나 인터럽트될 때까지 {@link LockSupport#park(Object)}로 잠든다. 이미 불렸으면 바로 돌아온다.
	 * 인터럽트로 돌아와도 예외는 없고, 인터럽트 플래그는 켜진 채 남는다.
	 */
	public void park() {
		waiter = Thread.currentThread();
		// 플래그가 켜져 있으면 park가 계속 바로 돌아오므로, 루프 조건에 넣어야 바쁜 대기를 피한다.
		while (!signaled && !Thread.currentThread().isInterrupted()) {
			LockSupport.park(this);
		}
	}

	/** 신호를 남기고, 잠든 대기자가 있으면 {@link LockSupport#unpark(Thread)}로 깨운다. */
	public void unpark() {
		// 순서가 중요하다: 플래그를 먼저 세워야 한다. 이 스레드가 waiter를 아직 null로 읽었다면 대기자는 아직 루프 조건을
		// 확인하기 전이므로, 그때 signaled=true를 보고 잠들지 않는다.
		signaled = true;
		Thread current = waiter;
		if (current != null) {
			LockSupport.unpark(current);
		}
	}

}
