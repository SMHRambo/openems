package io.openems.api.channel;

import java.math.BigInteger;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Stack;

import org.eclipse.jdt.annotation.Nullable;

import io.openems.api.device.nature.DeviceNature;
import io.openems.api.exception.WriteChannelException;

/**
 * Defines a writeable {@link Channel}. It handles a specific writeValue or boundaries for a writeValue.
 * Receiving the writeValue behaves similar to a {@link Stack}:
 * - Use {@link setMinWriteValue()} or {@link setMaxWriteValue()} to define a boundary.
 * - Use {@link pushWriteValue()} to set a specific value.
 * - Use {@link hasWriteValue()} to see if a value or a boundary was set.
 * - Use {@link peekWriteValue()} to receive the value.
 * - Use {@link popWriteValue()} to receive the value or a value that was derived from the min and max boundaries and
 * initialize the {@link WriteableChannel}.
 * - The {@link DeviceNature} is internally calling {@link popRawWriteValue()} to receive the value in a format suitable
 * for writing to hardware.
 *
 * @author stefan.feilmeier
 */
public class WriteableChannel extends Channel {
	protected final Channel maxWriteValueChannel;
	protected final Channel minWriteValueChannel;
	private BigInteger maxWriteValue = null;
	private BigInteger minWriteValue = null;
	private BigInteger writeValue = null;

	public WriteableChannel(DeviceNature nature, String unit, BigInteger minValue, BigInteger maxValue,
			BigInteger multiplier, BigInteger delta, Map<BigInteger, String> labels, BigInteger minWriteValue,
			Channel minWriteValueChannel, BigInteger maxWriteValue, Channel maxWriteValueChannel) {
		super(nature, unit, minValue, maxValue, multiplier, delta, labels);
		this.minWriteValue = minWriteValue;
		this.minWriteValueChannel = minWriteValueChannel;
		this.maxWriteValue = maxWriteValue;
		this.maxWriteValueChannel = maxWriteValueChannel;
	}

	/**
	 * Returns the multiplier, required to set the value to the hardware
	 *
	 * @return
	 */
	@Nullable
	public BigInteger getMultiplier() {
		return multiplier;
	}

	/**
	 * Checks if a fixed value or a boundary was set.
	 *
	 * @return true if anything was set.
	 */
	public boolean hasWriteValue() {
		return (writeValue != null || minWriteValue != null || maxWriteValue != null);
	}

	/**
	 * Returns the set Max boundary.
	 *
	 * @return
	 */
	@Nullable
	public BigInteger peekMaxWriteValue() {
		if (maxWriteValue != null) {
			return maxWriteValue;
		} else if (maxWriteValueChannel != null) {
			log.info("maxWriteValueChannel " + maxWriteValueChannel.getValueOrNull());
			return maxWriteValueChannel.getValueOrNull();
		} else {
			return null;
		}
	}

	/**
	 * Returns the set Min boundary.
	 *
	 * @return
	 */
	@Nullable
	public BigInteger peekMinWriteValue() {
		if (minWriteValue != null) {
			return minWriteValue;
		} else if (minWriteValueChannel != null) {
			log.info("minWriteValueChannel " + minWriteValueChannel.getValueOrNull());
			return minWriteValueChannel.getValueOrNull();
		} else {
			return null;
		}
	}

	/**
	 * Returns the fixed value.
	 *
	 * @return
	 */
	@Nullable
	public BigInteger peekWriteValue() {
		BigInteger result;
		if (this.writeValue != null) {
			// fixed value exists: return it
			result = this.writeValue;
		} else { // this.writeValue == null
			if (peekMaxWriteValue() != null) {
				if (peekMinWriteValue() != null) {
					// Min+Max exist: return average value
					result = minWriteValue.add(peekMaxWriteValue()).divide(BigInteger.valueOf(2));
				} else { // this.minWriteValue == null
					// only Max exists: return it
					result = peekMaxWriteValue();
				}
			} else { // this.maxWriteValue == null
				if (peekMinWriteValue() != null) {
					// only Min exist: return it
					result = peekMinWriteValue();
				} else { // this.minWriteValue == null
					// No value exists: return null
					result = null;
				}
			}
		}
		return result;
	}

	/**
	 * Returns the value or a value that was derived from the Min and Max boundaries in a format suitable
	 * for writing to hardware and initializes the
	 * {@link WriteableChannel}. This method is called internally by {@link DeviceNature}.
	 *
	 * @return
	 */
	public BigInteger popRawWriteValue() {
		BigInteger value = popWriteValue();
		if (value == null) {
			return value;
		}
		return value.add(delta).divide(multiplier);
	}

	/**
	 * Sets the Max boundary.
	 *
	 * @param maxValue
	 * @return
	 * @throws WriteChannelException
	 */
	public BigInteger pushMaxWriteValue(BigInteger maxValue) throws WriteChannelException {
		maxValue = roundToHardwarePrecision(maxValue);
		checkValueBoundaries(maxValue);
		this.maxWriteValue = maxValue;
		return maxValue;
	}

	/**
	 * Sets both Max and Min boundaries
	 *
	 * @param minValue
	 * @param maxValue
	 * @throws WriteChannelException
	 */
	public void pushMinMaxNewValue(BigInteger minValue, BigInteger maxValue) throws WriteChannelException {
		pushMinWriteValue(minValue);
		pushMaxWriteValue(maxValue);
	}

	public BigInteger pushMinWriteValue(BigInteger minValue) throws WriteChannelException {
		minValue = roundToHardwarePrecision(minValue);
		checkValueBoundaries(minValue);
		this.minWriteValue = minValue;
		return minValue;
	}

	/**
	 * Set a new value for this Channel
	 *
	 * @param writeValue
	 * @throws WriteChannelException
	 */
	public BigInteger pushWriteValue(BigInteger writeValue) throws WriteChannelException {
		writeValue = roundToHardwarePrecision(writeValue);
		checkValueBoundaries(writeValue);
		this.writeValue = writeValue;
		pushMinMaxNewValue(writeValue, writeValue);
		return writeValue;
	}

	/**
	 * Set a new value for this Channel using a Label
	 *
	 * @param writeValue
	 * @throws WriteChannelException
	 */
	public BigInteger pushWriteValue(String label) throws WriteChannelException {
		if (labels == null) {
			throw new WriteChannelException(
					"Label [" + label + "] not found. No labels set for Channel [" + getAddress() + "]");
		} else if (!labels.containsValue(label)) {
			throw new WriteChannelException("Label [" + label + "] not found: [" + labels.values() + "]");
		}
		for (Entry<BigInteger, String> entry : labels.entrySet()) {
			if (entry.getValue().equals(label)) {
				return pushWriteValue(entry.getKey());
			}
		}
		throw new WriteChannelException("Unexpected error in 'pushWriteValue()'-method with label [" + label
				+ "] for Channel [" + getAddress() + "]");
	}

	private void checkValueBoundaries(BigInteger value) throws WriteChannelException {
		if (this.writeValue != null) {
			if (value.compareTo(this.writeValue) != 0) {
				throwOutOfBoundariesException(value);
			}
		}
		if (this.minValue != null) {
			if (value.compareTo(this.minValue) < 0) {
				throwOutOfBoundariesException(value);
			}
		}
		if (this.minWriteValue != null) {
			if (value.compareTo(this.minWriteValue) < 0) {
				throwOutOfBoundariesException(value);
			}
		}
		if (this.maxValue != null) {
			if (value.compareTo(this.maxValue) > 0) {
				throwOutOfBoundariesException(value);
			}
		}
		if (this.maxWriteValue != null) {
			if (value.compareTo(this.maxWriteValue) > 0) {
				throwOutOfBoundariesException(value);
			}
		}
	}

	/**
	 * Returns the value or a value that was derived from the Min and Max boundaries and initializes the
	 * {@link WriteableChannel}.
	 *
	 * @return
	 */
	@Nullable
	private BigInteger popWriteValue() {
		BigInteger result = peekWriteValue();
		this.writeValue = null;
		this.minWriteValue = null;
		this.maxWriteValue = null;
		return result;
	}

	private BigInteger roundToHardwarePrecision(BigInteger value) {
		BigInteger[] division = value.divideAndRemainder(multiplier);
		if (division[1] != BigInteger.ZERO) {
			BigInteger roundedValue = division[0].multiply(multiplier);
			log.warn("Value [" + value + "] is too precise for device. Will round to [" + roundedValue + "]");
		}
		return value;
	}

	private void throwOutOfBoundariesException(BigInteger value) throws WriteChannelException {
		throw new WriteChannelException(
				"Value [" + value + "] is out of boundaries: fixed [" + this.writeValue + "], min [" + this.minValue
						+ "/" + this.minWriteValue + "], max [" + this.maxValue + "/" + this.maxWriteValue + "]");
	}

}
