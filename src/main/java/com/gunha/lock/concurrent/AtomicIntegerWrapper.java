package com.gunha.lock.concurrent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * {@link AtomicInteger}를 감싼 래퍼. {@code compareAndSet}으로 CAS 재시도 루프를 직접 써 보고 실패 횟수를 세어,
 * 한 명령으로 끝나는 {@code incrementAndGet}과 비교한다. 락 없이(lock-free) 동작한다.
 *
 * <h2>CAS (compare-and-swap)</h2>
 * "메모리 값이 기대값과 같으면 새 값으로 바꾸고 true, 아니면 아무것도 안 하고 false"를 CPU 명령 하나로 원자적으로 한다.
 * <ul>
 * <li>x86: {@code lock cmpxchg}. {@code lock} 접두는 그 캐시 라인을 이 코어가 독점(MESI의 Modified 상태)한 채
 *     읽기·비교·쓰기를 끝내게 한다. 동시에 full memory fence 역할도 한다.</li>
 * <li>ARM64: ARMv8.1 LSE의 {@code casal} 명령, 또는 {@code ldaxr}/{@code stlxr}(LL/SC) 루프.</li>
 * <li>자바에서는 {@code VarHandle}/{@code Unsafe}의 compareAndSet을 JIT이 이 명령으로 바꾼다(intrinsic).</li>
 * </ul>
 *
 * <h2>락과 비교</h2>
 * <ul>
 * <li>스레드가 잠들지 않는다. park도 OS 호출도 컨텍스트 스위치도 없다. 경합이 없으면 락도 CAS 한 번이라 비슷하지만,
 *     경합이 생겨도 잠들지 않으므로 짧은 갱신에서는 락보다 빠르다.</li>
 * <li>대신 경합이 심하면 CAS 실패와 재시도가 늘고, 코어들이 같은 캐시 라인을 서로 뺏어 오느라(캐시 라인 핑퐁) 느려진다.
 *     이 경우가 {@link LongAdder}({@link LongAdderWrapper})의 영역이다.</li>
 * <li>변수 <b>하나</b>만 원자적으로 바꿀 수 있다. 여러 필드를 함께 바꿔야 하면 락이나 불변 객체 + {@code AtomicReference}를 쓴다.</li>
 * <li>ABA 문제: 값이 A → B → A로 바뀌면 CAS는 변화를 모른다. 필요하면 {@code AtomicStampedReference}로 버전을 붙인다.</li>
 * </ul>
 *
 * <h2>x86에서 incrementAndGet은 명령 하나다</h2>
 * {@link AtomicInteger#incrementAndGet()}은 x86에서 JIT이 {@code lock xadd}(fetch-and-add)
 * 명령 하나로 바꾼다(intrinsic). 이 명령은 실패하지 않으므로 재시도 루프도 없다.
 * 모든 환경에서 그런 것은 아니다. 자바 소스({@code Unsafe.getAndAddInt})와 인터프리터는 CAS 루프이고,
 * LSE가 없는 ARM64에서는 LL/SC 루프가 된다. ARMv8.1 LSE가 있으면 {@code ldaddal} 명령 하나다.
 * {@link AtomicInteger#compareAndSet(int, int)}이 실패할 때마다 다시 읽고 부르는 루프와 비교해 볼 것.
 *
 * <h2>테스트로 확인</h2>
 * CAS가 실패하면 다시 읽고 재시도하므로 값이 유실되지 않는다.
 * {@snippet file="com/gunha/lock/concurrent/AtomicIntegerWrapperTest.java" region="cas-loop"}
 */
public class AtomicIntegerWrapper {

	private final AtomicInteger value = new AtomicInteger();

	private final LongAdder compareAndSetFailures = new LongAdder();

	/**
	 * {@code incrementAndGet}을 {@code compareAndSet} 재시도 루프로 직접 구현한다.
	 * 읽고 → 계산하고 → CAS로 쓰기를 시도한다. 그사이 누가 바꿨으면 실패하므로 처음부터 다시 한다.
	 */
	public int incrementWithCompareAndSet() {
		while (true) {
			int current = value.get();
			int next = current + 1;
			if (value.compareAndSet(current, next)) {
				return next;
			}
			compareAndSetFailures.increment();
		}
	}

	/** {@link AtomicInteger#incrementAndGet()}에 그대로 위임한다. 이 래퍼 쪽에는 재시도 루프가 없다(x86 JIT에서는 하드웨어 쪽에도 없다). */
	public int incrementAndGet() {
		return value.incrementAndGet();
	}

	public int get() {
		return value.get();
	}

	/** {@link #incrementWithCompareAndSet()}에서 {@code compareAndSet}이 실패해 다시 시도한 횟수. 경합 정도를 보여 준다. */
	public long compareAndSetFailures() {
		return compareAndSetFailures.sum();
	}

}
