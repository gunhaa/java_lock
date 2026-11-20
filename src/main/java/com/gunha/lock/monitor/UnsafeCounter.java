package com.gunha.lock.monitor;

/**
 * 락 없이 {@code volatile}만 붙인 카운터. 여러 스레드가 동시에 증가시키면 값이 유실된다.
 *
 * <h2>왜 깨지는가: count++는 한 동작이 아니다</h2>
 * {@code count++}는 바이트코드로 다음 여섯 명령이고, 핵심은 읽기·더하기·쓰기 세 단계다.
 * <pre>
 * aload_0; dup
 * getfield count   // 1. 읽기
 * iconst_1; iadd   // 2. 더하기 (스레드 자신의 오퍼랜드 스택/레지스터에서)
 * putfield count   // 3. 쓰기
 * </pre>
 * 스레드 A와 B가 둘 다 5를 읽고 각자 6을 쓰면, 증가 두 번이 한 번으로 줄어든다(lost update).
 * 읽기와 쓰기 사이에 다른 스레드가 끼어드는 것을 막아야 하는데, 이것이 원자성(atomicity)이고 락이 하는 일이다.
 *
 * <h2>volatile이 보장하는 것과 못 하는 것</h2>
 * <ul>
 * <li>보장: 가시성과 순서. volatile 쓰기가 쓴 값을 읽은 스레드는 쓴 스레드가 그 쓰기 전에 한 동작도 모두 본다(release/acquire).
 *     "즉시" 보인다는 뜻은 아니다. 게다가 volatile 접근끼리는 모든 스레드가 동의하는 하나의 순서를 가지므로,
 *     x86에서는 volatile 쓰기 뒤에 {@code lock addl}(StoreLoad 배리어)이 붙는다. 자세한 규칙은 <a href="https://github.com/gunhaa/java_lock/blob/main/docs/02-java-memory-model.md#volatile">docs/02 2.5절</a>.</li>
 * <li>못 하는 것: 읽기-수정-쓰기의 원자성. 위 1~3 사이 끼어들기는 그대로 일어난다.</li>
 * </ul>
 * volatile을 붙인 이유는 오히려 실험을 확실하게 하기 위해서다. volatile이 없으면 JIT가 루프 안의 증가를
 * 레지스터에 모았다가 한 번에 쓰도록 바꿀 수 있어서, 유실 정도가 JIT 결정에 따라 극단적으로 달라질 수 있다.
 *
 * <h2>CPU 관점</h2>
 * 캐시 일관성 프로토콜(MESI)은 "같은 라인의 최신 값"은 보장하지만 "읽고 쓰는 사이에 남이 못 쓰게"는 보장하지 않는다.
 * 그 보장은 x86의 {@code lock} 접두 명령(예: {@code lock xadd})처럼 특별한 명령이나 락이 있어야 한다(<a href="https://github.com/gunhaa/java_lock/blob/main/docs/03-hardware-and-os.md#cache-coherence">docs/03 3.2절</a>, <a href="https://github.com/gunhaa/java_lock/blob/main/docs/03-hardware-and-os.md#atomic-instructions">3.5절</a>).
 *
 * <h2>테스트로 확인</h2>
 * 멀티 코어에서 8개 스레드가 10만 번씩 증가시키면 결과가 대개 80만보다 작다.
 * {@snippet file="com/gunha/lock/monitor/UnsafeCounterTest.java" region="lost-update"}
 *
 * @see SynchronizedWrapper
 * @see com.gunha.lock.concurrent.AtomicIntegerWrapper
 */
public class UnsafeCounter {

	private volatile int count;

	public void increment() {
		count++;
	}

	public int get() {
		return count;
	}

}
