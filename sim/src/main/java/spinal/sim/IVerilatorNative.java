/**
 * Verilator JNI Interface Definition File
 *
 * File Purpose:
 * Defines the JNI (Java Native Interface) between Java layer and Verilator
 * compiled C++ model. This is the only entry point for Java code to access
 * Verilator simulator in SpinalHDL simulation system.
 *
 * Position in Simulation Flow:
 * SimVerilator -> IVerilatorNative -> JNI Layer -> Verilator C++ Model -> Hardware Simulation
 *
 * Interface Function Categories:
 * 1. Simulation Instance Management:
 *    - newHandle(): Create simulation instance
 *    - deleteHandle(): Destroy simulation instance
 *
 * 2. Simulation Control:
 *    - eval(): Advance simulation by one delta cycle
 *    - sleep(): Advance specified simulation cycles
 *    - get_time_precision(): Get simulation time precision
 *
 * 3. Signal Access:
 *    - getU64/setU64: 64-bit signal read/write
 *    - getAU8/setAU8: Byte array signal read/write
 *    - _mem suffix: Support indexed access for memory signals
 *
 * 4. Waveform Control:
 *    - enableWave/disableWave: Dynamic waveform recording control
 *
 * Implementation Notes:
 * - This interface is implemented by dynamically generated Java class from VerilatorBackend
 * - Each method corresponds to a JNI function in C++ layer
 * - Supports concurrent execution of multiple simulation instances
 */

package spinal.sim;

/**
 * Verilator native interface
 * Defines JNI interface between Java and Verilator compiled C++ model
 * Provides simulation control, signal access and waveform control functions
 */
public interface IVerilatorNative {
    // Simulation instance management
    public long newHandle(String name, String wavePath, int seed);    // Create new simulation handle
    public void deleteHandle(long handle);                            // Delete simulation handle

    // Simulation control
    public boolean eval(long handle);                                 // Evaluate simulation one cycle
    public int get_time_precision(long handle);                       // Get time precision
    public void sleep(long handle, long cycles);                      // Sleep for specified cycles

    // 64-bit signal access
    public long getU64(long handle, int id);                          // Read 64-bit unsigned signal
    public void setU64(long handle, int id, long value);              // Write 64-bit unsigned signal

    // Byte array signal access
    public void getAU8(long handle, int id, byte[] value);            // Read byte array signal
    public void setAU8(long handle, int id, byte[] value, int length); // Write byte array signal

    // Memory signal access (with index)
    public long getU64_mem(long handle, int id, long index);          // Read 64-bit signal from memory
    public void setU64_mem(long handle, int id, long value, long index); // Write 64-bit signal to memory
    public void getAU8_mem(long handle, int id, byte[] value, long index); // Read byte array signal from memory
    public void setAU8_mem(long handle, int id, byte[] value, int length, long index); // Write byte array signal to memory

    // Waveform control
    public void enableWave(long handle);                              // Enable waveform recording
    public void disableWave(long handle);                             // Disable waveform recording
}

