package io.openems.edge.ess.mr.gridcon;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.BitSet;
import java.util.function.Consumer;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.FloatReadChannel;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.channel.merger.ChannelMergerSumFloat;
import io.openems.edge.common.channel.merger.ChannelMergerSumInteger;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.mr.gridcon.enums.CCUState;
import io.openems.edge.ess.mr.gridcon.enums.GridConChannelId;
import io.openems.edge.ess.mr.gridcon.enums.PCSControlWordBitPosition;
import io.openems.edge.ess.mr.gridcon.enums.PControlMode;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.io.api.DigitalInput;
import io.openems.edge.meter.api.SymmetricMeter;

/**
 * This class handles the communication between ems and a gridcon.
 */
@Designate(ocd = Config.class, factory = true)
@Component( //
		name = "Ess.MR.Gridcon", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
)
public class GridconPCS extends AbstractOpenemsModbusComponent
		implements ManagedSymmetricEss, SymmetricEss, OpenemsComponent, EventHandler {

	private final Logger log = LoggerFactory.getLogger(GridconPCS.class);

	protected static final float MAX_POWER_W = 125 * 1000;
	protected static final float MAX_CHARGE_W = 86 * 1000;
	protected static final float MAX_DISCHARGE_W = 86 * 1000;

	private ChannelAddress inputChannelAddress = null;
	private int gridFreq;
	private int gridVolt;
	private float essFreq;
	private float essVolt;
	private int freqDiff;
	private int voltDiff;
	private boolean bridgeContactorRead;
	private boolean bridgeContactorWrite;
	private boolean mainSwitch = true;
	LocalDateTime currentTime;
	LocalDateTime nextTime;

	static final int MAX_APPARENT_POWER = (int) MAX_POWER_W; // TODO Checkif correct
//	private CircleConstraint maxApparentPowerConstraint = null;
	BitSet commandControlWord = new BitSet(32);

	@Reference
	private Power power;

	@Reference
	protected ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	Battery battery1;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	SymmetricMeter meter;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	Battery battery2;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	Battery battery3;

	public GridconPCS() {
		Utils.initializeChannels(this).forEach(channel -> this.addChannel(channel));
	}

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	private DigitalInput inputComponent = null;

	@Activate
	void activate(ComponentContext context, Config config) throws OpenemsException {
		// update filter for 'battery1'
		if (OpenemsComponent.updateReferenceFilter(this.cm, config.service_pid(), "Battery1", config.battery1_id())) {
			return;
		}

		// update filter for 'battery2'
		if (OpenemsComponent.updateReferenceFilter(this.cm, config.service_pid(), "Battery2", config.battery2_id())) {
			return;
		}

		// update filter for 'battery3'
		if (OpenemsComponent.updateReferenceFilter(this.cm, config.service_pid(), "Battery3", config.battery3_id())) {
			return;
		}
		// update filter for 'Janitza96 Meter'
		if (OpenemsComponent.updateReferenceFilter(this.cm, config.service_pid(), "Janitza96Meter", config.meter())) {
			return;
		}
		this.inputChannelAddress = ChannelAddress.fromString(config.inputChannelAddress());

		if (OpenemsComponent.updateReferenceFilter(this.cm, config.service_pid(), "inputComponent",
				this.inputChannelAddress.getComponentId())) {
			return;
		}
//		
		/*
		 * Initialize Power
		 */
//		int max = 5000;
//		int min = -5000;
//		
//		this.getPower().addSimpleConstraint(this, ConstraintType.STATIC, Phase.ALL, Pwr.ACTIVE, Relationship.LESS_OR_EQUALS, max);
//		this.getPower().addSimpleConstraint(this, ConstraintType.STATIC, Phase.ALL, Pwr.ACTIVE, Relationship.GREATER_OR_EQUALS, min);
//		
		// Max Apparent
		// TODO adjust apparent power from modbus element
//		this.maxApparentPowerConstraint = new CircleConstraint(this, MAX_APPARENT_POWER);


		super.activate(context, config.service_pid(), config.id(), config.enabled(), config.unit_id(), this.cm,
				"Modbus", config.modbus_id());
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	
	
	/**
	 * This method tries to turn on the gridcon/ set it in a RUN state.
	 */
	private void handleStateMachine() {
		// TODO
		// see Software manual chapter 5.1
		
		if (isOnGridMode()) {
			// Bridge Contactor Normall Closed(NC)
			// iF DI2=1(bridge Contactor) make sync, DI2=0 run until grid come back, when
			// its back
			// Open Switch with controlling DI2
			if (!this.bridgeContactorRead && gridFreq != 0) {
				// Bridge Contactor Write = DO1
				// set DO1 =1
			}

			commandControlWord.set(PCSControlWordBitPosition.PLAY.getBitPosition(), false);
			commandControlWord.set(PCSControlWordBitPosition.READY.getBitPosition(), false);
			commandControlWord.set(PCSControlWordBitPosition.ACKNOWLEDGE.getBitPosition(), false);
			commandControlWord.set(PCSControlWordBitPosition.STOP.getBitPosition(), false);

			commandControlWord.set(PCSControlWordBitPosition.SYNC_APPROVAL.getBitPosition(), true);
			commandControlWord.set(PCSControlWordBitPosition.MODE_SELECTION.getBitPosition(), true);
			commandControlWord.set(PCSControlWordBitPosition.ACTIVATE_SHORT_CIRCUIT_HANDLING.getBitPosition(), true);

			commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_1.getBitPosition(), false);
			commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_2.getBitPosition(), false);
			commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_3.getBitPosition(), false);
			commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_4.getBitPosition(), false);
			
			switch (getCurrentState()) {
			case DERATING_HARMONICS:
				break;
			case DERATING_POWER:
				break;
			case ERROR:
				doErrorHandling();
				break;
			case IDLE:
				startSystem();
				break;
			case OVERLOAD:
				break;
			case PAUSE:
				break;
			case PRECHARGE:
				break;
			case READY:
				break;
			case RUN:
				break;
			case SHORT_CIRCUIT_DETECTED:
				break;
			case SIA_ACTIVE:
				break;
			case STOP_PRECHARGE:
				break;
			case UNDEFINED:
				break;
			case VOLTAGE_RAMPING_UP:
				break;
			}

			writeValueToChannel(GridConChannelId.PCS_COMMAND_ERROR_CODE_FEEDBACK, 0);
			writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_Q_REF, 0);
			writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_P_REF, 0);
			/**
			 * Always write values for frequency and voltage to gridcon, because in case of
			 * blackstart mode if we write '0' to gridcon the systems tries to regulate
			 * frequency and voltage to zero which would be bad for Mr. Gridcon's health
			 */
			writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_U0, 1.0f);
			writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_F0, 1.0f);
			writeDateAndTime();

			writeCCUControlParameters(PControlMode.ACTIVE_POWER_CONTROL);
			writeIPUParameters(1f, 1f, 1f, MAX_DISCHARGE_W, MAX_DISCHARGE_W, MAX_DISCHARGE_W, MAX_CHARGE_W,
					MAX_CHARGE_W, MAX_CHARGE_W);

//			//TODO This is to make the choco solver working, where should we put this?
			((ManagedSymmetricEss) this).getAllowedCharge().setNextValue(-MAX_APPARENT_POWER);
			((ManagedSymmetricEss) this).getAllowedDischarge().setNextValue(MAX_APPARENT_POWER);
			
			Integer value = convertToInteger(commandControlWord);
			writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_WORD, value);

//			
		} else {
			frequencySynch();
		}
	}

	private void writeCCUControlParameters(PControlMode mode) {

		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_U_Q_DROOP_MAIN, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_U_Q_DROOP_T1_MAIN, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_F_P_DRROP_MAIN, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_F_P_DROOP_T1_MAIN, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_Q_U_DROOP_MAIN, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_Q_U_DEAD_BAND, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_Q_LIMIT, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_P_F_DROOP_MAIN, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_P_F_DEAD_BAND, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_P_U_DROOP, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_P_U_DEAD_BAND, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_P_U_MAX_CHARGE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_P_U_MAX_DISCHARGE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_P_CONTROL_LIM_TWO, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_P_CONTROL_LIM_ONE, 0f);
		// the only relevant parameter is 'P Control Mode' which should be set to
		// 'Active power control' in case of on grid usage
		writeValueToChannel(GridConChannelId.PCS_CONTROL_PARAMETER_P_CONTROL_MODE, mode.getFloatValue()); //
	}

	// Normal mode with current control
	private boolean isOnGridMode() {
		// TODO component gets the information from "Netztrennschalter"
		return true;
	}

	/**
	 * This writes the current time into the necessary channel for the
	 * communicationprotocol with the gridcon.
	 */
	private void writeDateAndTime() {
		LocalDateTime time = LocalDateTime.now();
		byte dayOfWeek = (byte) time.getDayOfWeek().ordinal();
		byte day = (byte) time.getDayOfMonth();
		byte month = (byte) time.getMonth().getValue();
		byte year = (byte) (time.getYear() - 2000); // 0 == year 1900 in the protocol

		Integer dateInteger = convertToInteger(BitSet.valueOf(new byte[] { day, dayOfWeek, year, month }));

		byte seconds = (byte) time.getSecond();
		byte minutes = (byte) time.getMinute();
		byte hours = (byte) time.getHour();

		// second byte is unused
		Integer timeInteger = convertToInteger(BitSet.valueOf(new byte[] { seconds, 0, hours, minutes }));

		writeValueToChannel(GridConChannelId.PCS_COMMAND_TIME_SYNC_DATE, dateInteger);
		writeValueToChannel(GridConChannelId.PCS_COMMAND_TIME_SYNC_TIME, timeInteger);

	}

	/**
	 * This turns on the system by enabling ALL IPUs.
	 */
	private void startSystem() {
		log.info("Try to start system");
		/*
		 * Coming from state idle first write 800V to IPU4 voltage setpoint, set "73" to
		 * DCDC String Control Mode of IPU4 and "1" to Weight String A, B, C ==> i.e.
		 * all 3 IPUs are weighted equally write -86000 to Pmax discharge Iref String A,
		 * B, C, write 86000 to Pmax Charge DCDC Str Mode of IPU 1, 2, 3 set P Control
		 * mode to "Act Pow Ctrl" (hex 4000 = mode power limiter, 0 = disabled, hex 3F80
		 * = active power control) and Mode Sel to "Current Control" s--> ee pic
		 * start0.png in doc folder
		 * 
		 * enable "Sync Approval" and "Ena IPU 4" and PLAY command -> system should
		 * change state to "RUN" --> see pic start1.png
		 * 
		 * after that enable IPU 1, 2, 3, if they have reached state "RUN" (=14) power
		 * can be set (from 0..1 (1 = max system power = 125 kW) , i.e. 0,05 is equal to
		 * 6.250 W same for reactive power see pic start2.png
		 * 
		 * "Normal mode" is reached now
		 */

		// enable "Sync Approval" and "Ena IPU 4, 3, 2, 1" and PLAY command -> system
		// should change state to "RUN"
		commandControlWord.set(PCSControlWordBitPosition.PLAY.getBitPosition(), true);

		commandControlWord.set(PCSControlWordBitPosition.SYNC_APPROVAL.getBitPosition(), true);
		commandControlWord.set(PCSControlWordBitPosition.MODE_SELECTION.getBitPosition(), true);
		commandControlWord.set(PCSControlWordBitPosition.ACTIVATE_SHORT_CIRCUIT_HANDLING.getBitPosition(), true);

		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_1.getBitPosition(), false);
		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_2.getBitPosition(), false);
		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_3.getBitPosition(), false);
		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_4.getBitPosition(), false);
	}

	private void stopSystem() {
		// TODO
		log.info("Try to stop system");

		// disable "Sync Approval" and "Ena IPU 4, 3, 2, 1" and add STOP command ->
		// system should change state to "IDLE"
		commandControlWord.set(PCSControlWordBitPosition.STOP.getBitPosition(), true);
		commandControlWord.set(PCSControlWordBitPosition.SYNC_APPROVAL.getBitPosition(), false);
		commandControlWord.set(PCSControlWordBitPosition.BLACKSTART_APPROVAL.getBitPosition(), false);
		commandControlWord.set(PCSControlWordBitPosition.MODE_SELECTION.getBitPosition(), true);

		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_1.getBitPosition(), true);
		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_2.getBitPosition(), true);
		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_3.getBitPosition(), true);
		commandControlWord.set(PCSControlWordBitPosition.DISABLE_IPU_4.getBitPosition(), true);
	}

	/**
	 * This converts a Bitset to its decimal value. Only works as long as the value
	 * of the Bitset does not exceed the range of an Integer.
	 * 
	 * @param bitSet The Bitset which should be converted
	 * @return The converted Integer
	 */
	private Integer convertToInteger(BitSet bitSet) {
		long[] l = bitSet.toLongArray();

		if (l.length == 0) {
			return 0;
		}
		return (int) l[0];
	}

	/**
	 * Writes parameters to all 4 IPUs !! Max charge/discharge power for IPUs always
	 * in absolute values !!
	 */
	private void writeIPUParameters(float weightA, float weightB, float weightC, float pMaxDischargeIPU1,
			float pMaxDischargeIPU2, float pMaxDischargeIPU3, float pMaxChargeIPU1, float pMaxChargeIPU2,
			float pMaxChargeIPU3) {

		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_DC_VOLTAGE_SETPOINT, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_DC_CURRENT_SETPOINT, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_U0_OFFSET_TO_CCU_VALUE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_F0_OFFSET_TO_CCU_VALUE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_Q_REF_OFFSET_TO_CCU_VALUE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_P_REF_OFFSET_TO_CCU_VALUE, 0f);

		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_DC_VOLTAGE_SETPOINT, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_DC_CURRENT_SETPOINT, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_U0_OFFSET_TO_CCU_VALUE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_F0_OFFSET_TO_CCU_VALUE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_Q_REF_OFFSET_TO_CCU_VALUE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_P_REF_OFFSET_TO_CCU_VALUE, 0f);

		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_DC_VOLTAGE_SETPOINT, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_DC_CURRENT_SETPOINT, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_U0_OFFSET_TO_CCU_VALUE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_F0_OFFSET_TO_CCU_VALUE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_Q_REF_OFFSET_TO_CCU_VALUE, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_P_REF_OFFSET_TO_CCU_VALUE, 0f);

		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_DC_VOLTAGE_SETPOINT, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_A, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_B, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_C, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_I_REF_STRING_A, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_I_REF_STRING_B, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_I_REF_STRING_C, 0f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_DC_DC_STRING_CONTROL_MODE,
				0f); //

		// The value of 800 Volt is given by MR as a good reference value
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_DC_VOLTAGE_SETPOINT, 800f);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_A, weightA);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_B, weightB);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_C, weightC);
		// The value '73' implies that all 3 strings are in weighting mode
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_DC_DC_STRING_CONTROL_MODE,
				73f); //

		// Gridcon needs negative values for discharge values
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_P_MAX_DISCHARGE, -pMaxDischargeIPU1);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_P_MAX_DISCHARGE, -pMaxDischargeIPU2);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_P_MAX_DISCHARGE, -pMaxDischargeIPU3);
		// Gridcon needs positive values for charge values
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_P_MAX_CHARGE, pMaxChargeIPU1);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_P_MAX_CHARGE, pMaxChargeIPU2);
		writeValueToChannel(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_P_MAX_CHARGE, pMaxChargeIPU3);
	}

	private void doErrorHandling() {
		if (isHardwareTrip()) {
			doHardRestart();
		} else {
			
			acknowledgeErrors();
		}

		// TODO
		// try to find out what kind of error it is,
		// disable IPUs, stopping system, then acknowledge errors, wait some seconds
		// if no errors are shown, then try to start system
//		stopSystem();		
	}

	private void doHardRestart() {
		// TODO Here we ned a component that allows us to switch off the power
	}

	private boolean isHardwareTrip() {
		// TODO Error codes are needed!!
		return false;
	}

	LocalDateTime lastTimeAcknowledgeCommandoWasSent;
	long ACKNOWLEDGE_TIME_SECONDS = 5;

	/**
	 * This sends an ACKNOWLEDGE message. This does not fix the error. If the error
	 * was fixed previously the system should continue operating normally. If not a
	 * manual restart may be necessary.
	 */
	private void acknowledgeErrors() {
		if (lastTimeAcknowledgeCommandoWasSent == null || LocalDateTime.now()
				.isAfter(lastTimeAcknowledgeCommandoWasSent.plusSeconds(ACKNOWLEDGE_TIME_SECONDS))) {
			commandControlWord.set(PCSControlWordBitPosition.ACKNOWLEDGE.getBitPosition(), true);
			lastTimeAcknowledgeCommandoWasSent = LocalDateTime.now();
		}
	}

	@Override
	public String debugLog() {
		return "Current state: " + getCurrentState().toString() //
				+ "essFreq: " + this.essFreq //
				+ "gridFrq: " + this.gridFreq;
	}

	private CCUState getCurrentState() {
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_IDLE)).value().asOptional()
				.orElse(false)) {
			return CCUState.IDLE;
		}

		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_PRECHARGE)).value().asOptional()
				.orElse(false)) {
			return CCUState.PRECHARGE;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_STOP_PRECHARGE)).value().asOptional()
				.orElse(false)) {
			return CCUState.STOP_PRECHARGE;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_READY)).value().asOptional()
				.orElse(false)) {
			return CCUState.READY;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_PAUSE)).value().asOptional()
				.orElse(false)) {
			return CCUState.PAUSE;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_RUN)).value().asOptional()
				.orElse(false)) {
			return CCUState.RUN;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_ERROR)).value().asOptional()
				.orElse(false)) {
			return CCUState.ERROR;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_VOLTAGE_RAMPING_UP)).value().asOptional()
				.orElse(false)) {
			return CCUState.VOLTAGE_RAMPING_UP;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_OVERLOAD)).value().asOptional()
				.orElse(false)) {
			return CCUState.OVERLOAD;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_SHORT_CIRCUIT_DETECTED)).value()
				.asOptional().orElse(false)) {
			return CCUState.SHORT_CIRCUIT_DETECTED;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_DERATING_POWER)).value().asOptional()
				.orElse(false)) {
			return CCUState.DERATING_POWER;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_DERATING_HARMONICS)).value().asOptional()
				.orElse(false)) {
			return CCUState.DERATING_HARMONICS;
		}
		if (((BooleanReadChannel) this.channel(GridConChannelId.PCS_CCU_STATE_SIA_ACTIVE)).value().asOptional()
				.orElse(false)) {
			return CCUState.SIA_ACTIVE;
		}

		return CCUState.UNDEFINED;
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public void applyPower(int activePower, int reactivePower) {
		if (getCurrentState() != CCUState.RUN) {
			return;
		}
		doStringWeighting(activePower, reactivePower);
		/*
		 * !! signum, MR calculates negative values as discharge, positive as charge.
		 * Gridcon sets the (dis)charge according to a percentage of the MAX_POWER. So
		 * 0.1 => 10% of max power. Values should never take values lower than -1 or
		 * higher than 1.
		 */
		float activePowerFactor = -activePower / MAX_POWER_W;
		float reactivePowerFactor = -reactivePower / MAX_POWER_W;


		writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_P_REF, activePowerFactor);
		writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_Q_REF, reactivePowerFactor);
	}

	private void doStringWeighting(int activePower, int reactivePower) {
		// weight according to battery ranges

		int weight1 = 0;
		int weight2 = 0;
		int weight3 = 0;

		// weight strings according to max allowed current
		// use values for discharging
		if (activePower > 0) {
			weight1 = battery1.getDischargeMaxCurrent().value().asOptional().orElse(0);
			weight2 = battery2.getDischargeMaxCurrent().value().asOptional().orElse(0);
			weight3 = battery3.getDischargeMaxCurrent().value().asOptional().orElse(0);
		} else { // use values for charging
			weight1 = battery1.getChargeMaxCurrent().value().asOptional().orElse(0);
			weight2 = battery2.getChargeMaxCurrent().value().asOptional().orElse(0);
			weight3 = battery3.getChargeMaxCurrent().value().asOptional().orElse(0);
		}

		// TODO discuss if this is correct!
		int maxChargePower1 = battery1.getChargeMaxCurrent().value().asOptional().orElse(0)
				* battery1.getChargeMaxVoltage().value().asOptional().orElse(0);
		int maxChargePower2 = battery2.getChargeMaxCurrent().value().asOptional().orElse(0)
				* battery2.getChargeMaxVoltage().value().asOptional().orElse(0);
		int maxChargePower3 = battery3.getChargeMaxCurrent().value().asOptional().orElse(0)
				* battery3.getChargeMaxVoltage().value().asOptional().orElse(0);

		int maxDischargePower1 = battery1.getDischargeMaxCurrent().value().asOptional().orElse(0)
				* battery1.getDischargeMinVoltage().value().asOptional().orElse(0);
		int maxDischargePower2 = battery2.getDischargeMaxCurrent().value().asOptional().orElse(0)
				* battery2.getDischargeMinVoltage().value().asOptional().orElse(0);
		int maxDischargePower3 = battery3.getDischargeMaxCurrent().value().asOptional().orElse(0)
				* battery3.getDischargeMinVoltage().value().asOptional().orElse(0);

		writeIPUParameters(weight1, weight2, weight3, maxDischargePower1, maxDischargePower2, maxDischargePower3,
				maxChargePower1, maxChargePower2, maxChargePower3);
	}

	/** Writes the given value into the channel */
	void writeValueToChannel(GridConChannelId channelId, Object value) {
		try {
			((WriteChannel<?>) this.channel(channelId)).setNextWriteValueFromObject(value);
		} catch (OpenemsException e) {
			e.printStackTrace();
			log.error("Problem occurred during writing '" + value + "' to channel " + channelId.name());
		}
	}

	@Override
	public int getPowerPrecision() {
		return 100;
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			handleStateMachine();
			calculateSoC();
			break;
		}
	}

	private void frequencySynch() {
		// Measured by Janitza96, grid Values
		this.gridFreq = this.meter.getFrequency().value().asOptional().orElse(0);
		this.gridVolt = this.meter.getVoltage().value().asOptional().orElse(0);

		// MR Inverter values
		Channel<Float> inverterFrequency = this.channel(GridConChannelId.PCS_CCU_FREQUENCY);
		Channel<Float> inverterVoltage = this.channel(GridConChannelId.PCS_CCU_VOLTAGE_U12);
		this.essFreq = inverterFrequency.value().asOptional().orElse(0f);
		this.essVolt = inverterVoltage.value().asOptional().orElse(0f);

		// Taking the differencess of values
		// Needs to consider; volt difference must be in interval of (-5, 15)
		// normally we are taking 230V and gridVolt-essVol could be min 225V or max 245V
		this.freqDiff = gridFreq - (int) (essFreq * 50000);
		this.voltDiff = gridVolt - (int) (essVolt * 230000);
		// TODO Check Emergency Mode!!!!
		
		BooleanReadChannel inputChannel = this.inputComponent.channel(this.inputChannelAddress.getChannelId());
		this.bridgeContactorRead = inputChannel.value().get();
		System.out.println("bridgeContactor : " + bridgeContactorRead);

		if (voltDiff < 15 && voltDiff > -5 && gridFreq != 0) {
			// TODO needs to change value
			float addFreq = (this.freqDiff / 2 + this.essFreq * 50) / 50;
			System.out.println("addFreq : " + addFreq);

			// Setting the frequency to MR
			writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_F0, 1.0004f);

			System.out.println("freqDiff : " + freqDiff + "----volt DIff : " + voltDiff);
			// If Main switch was not closed after 10 min, set the freq=50Hz and volt=230V
			this.currentTime = LocalDateTime.now(ZoneId.of("UTC"));
			if (this.nextTime == null) {
				this.nextTime = currentTime.plusSeconds(600);
			}

			// TODO DI1 didnt implemented just initialized
			// We can read Main switch position with DI1
			if (!currentTime.isBefore(nextTime) && !this.mainSwitch) {
				writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_U0, 1.0f);
				writeValueToChannel(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_F0, 1.0f);
			}

		}

	}

	private void calculateSoC() {
		double sumCapacity = 0;
		double sumCurrentCapacity = 0;
		for (Battery b : new Battery[] { battery1, battery2, battery3 }) {
			sumCapacity = sumCapacity + b.getCapacity().value().asOptional().orElse(0);
			sumCurrentCapacity = sumCurrentCapacity
					+ b.getCapacity().value().asOptional().orElse(0) * b.getSoc().value().orElse(0) / 100.0;
		}
		int soC = (int) (sumCurrentCapacity * 100 / sumCapacity);
		this.getSoc().setNextValue(soC);
	}

	@SuppressWarnings("unchecked")
	protected ModbusProtocol defineModbusProtocol(int unitId) {
		ModbusProtocol protocol = new ModbusProtocol(this, //
				new FC3ReadRegistersTask(32528, Priority.HIGH, // CCU state
						bm(new UnsignedDoublewordElement(32528)) //
								.m(GridConChannelId.PCS_CCU_STATE_IDLE, 0) //
								.m(GridConChannelId.PCS_CCU_STATE_PRECHARGE, 1) //
								.m(GridConChannelId.PCS_CCU_STATE_STOP_PRECHARGE, 2) //
								.m(GridConChannelId.PCS_CCU_STATE_READY, 3) //
								.m(GridConChannelId.PCS_CCU_STATE_PAUSE, 4) //
								.m(GridConChannelId.PCS_CCU_STATE_RUN, 5) //
								.m(GridConChannelId.PCS_CCU_STATE_ERROR, 6) //
								.m(GridConChannelId.PCS_CCU_STATE_VOLTAGE_RAMPING_UP, 7) //
								.m(GridConChannelId.PCS_CCU_STATE_OVERLOAD, 8) //
								.m(GridConChannelId.PCS_CCU_STATE_SHORT_CIRCUIT_DETECTED, 9) //
								.m(GridConChannelId.PCS_CCU_STATE_DERATING_POWER, 10) //
								.m(GridConChannelId.PCS_CCU_STATE_DERATING_HARMONICS, 11) //
								.m(GridConChannelId.PCS_CCU_STATE_SIA_ACTIVE, 12) //
								.build().wordOrder(WordOrder.LSWMSW), //
						m(GridConChannelId.PCS_CCU_ERROR_CODE,
								new UnsignedDoublewordElement(32530).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CCU_VOLTAGE_U12,
								new FloatDoublewordElement(32532).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CCU_VOLTAGE_U23,
								new FloatDoublewordElement(32534).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CCU_VOLTAGE_U31,
								new FloatDoublewordElement(32536).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CCU_CURRENT_IL1,
								new FloatDoublewordElement(32538).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CCU_CURRENT_IL2,
								new FloatDoublewordElement(32540).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CCU_CURRENT_IL3,
								new FloatDoublewordElement(32542).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CCU_POWER_P,
								new FloatDoublewordElement(32544).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CCU_POWER_Q,
								new FloatDoublewordElement(32546).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CCU_FREQUENCY,
								new FloatDoublewordElement(32548).wordOrder(WordOrder.LSWMSW)) //
				), new FC3ReadRegistersTask(33168, Priority.LOW, // IPU 1 state
						byteMap(new UnsignedDoublewordElement(33168)) //
								.mapByte(GridConChannelId.PCS_IPU_1_STATUS_STATUS_STATE_MACHINE, 0) //
								.mapByte(GridConChannelId.PCS_IPU_1_STATUS_STATUS_MCU, 1) //
								.build().wordOrder(WordOrder.LSWMSW), //
						m(GridConChannelId.PCS_IPU_1_STATUS_FILTER_CURRENT,
								new FloatDoublewordElement(33170).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_DC_LINK_POSITIVE_VOLTAGE,
								new FloatDoublewordElement(33172).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_DC_LINK_NEGATIVE_VOLTAGE,
								new FloatDoublewordElement(33174).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_DC_LINK_CURRENT,
								new FloatDoublewordElement(33176).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_DC_LINK_ACTIVE_POWER,
								new FloatDoublewordElement(33178).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_DC_LINK_UTILIZATION,
								new FloatDoublewordElement(33180).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_FAN_SPEED_MAX,
								new UnsignedDoublewordElement(33182).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_FAN_SPEED_MIN,
								new UnsignedDoublewordElement(33184).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_TEMPERATURE_IGBT_MAX,
								new FloatDoublewordElement(33186).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_TEMPERATURE_MCU_BOARD,
								new FloatDoublewordElement(33188).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_TEMPERATURE_GRID_CHOKE,
								new FloatDoublewordElement(33190).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_TEMPERATURE_INVERTER_CHOKE,
								new FloatDoublewordElement(33192).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_RESERVE_1,
								new FloatDoublewordElement(33194).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_RESERVE_2,
								new FloatDoublewordElement(33196).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_STATUS_RESERVE_3,
								new FloatDoublewordElement(33198).wordOrder(WordOrder.LSWMSW)) //
				), new FC3ReadRegistersTask(33200, Priority.LOW, // // IPU 2 state
						byteMap(new UnsignedDoublewordElement(33200)) //
								.mapByte(GridConChannelId.PCS_IPU_2_STATUS_STATUS_STATE_MACHINE, 0) //
								.mapByte(GridConChannelId.PCS_IPU_2_STATUS_STATUS_MCU, 1) //
								.build().wordOrder(WordOrder.LSWMSW), //
						m(GridConChannelId.PCS_IPU_2_STATUS_FILTER_CURRENT,
								new FloatDoublewordElement(33202).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_DC_LINK_POSITIVE_VOLTAGE,
								new FloatDoublewordElement(33204).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_DC_LINK_NEGATIVE_VOLTAGE,
								new FloatDoublewordElement(33206).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_DC_LINK_CURRENT,
								new FloatDoublewordElement(33208).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_DC_LINK_ACTIVE_POWER,
								new FloatDoublewordElement(33210).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_DC_LINK_UTILIZATION,
								new FloatDoublewordElement(33212).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_FAN_SPEED_MAX,
								new UnsignedDoublewordElement(33214).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_FAN_SPEED_MIN,
								new UnsignedDoublewordElement(33216).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_TEMPERATURE_IGBT_MAX,
								new FloatDoublewordElement(33218).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_TEMPERATURE_MCU_BOARD,
								new FloatDoublewordElement(33220).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_TEMPERATURE_GRID_CHOKE,
								new FloatDoublewordElement(33222).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_TEMPERATURE_INVERTER_CHOKE,
								new FloatDoublewordElement(33224).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_RESERVE_1,
								new FloatDoublewordElement(33226).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_RESERVE_2,
								new FloatDoublewordElement(33228).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_STATUS_RESERVE_3,
								new FloatDoublewordElement(33230).wordOrder(WordOrder.LSWMSW)) //
				), new FC3ReadRegistersTask(33232, Priority.LOW, //
						byteMap(new UnsignedDoublewordElement(33232)) // // IPU 3 state
								.mapByte(GridConChannelId.PCS_IPU_3_STATUS_STATUS_STATE_MACHINE, 0) //
								.mapByte(GridConChannelId.PCS_IPU_3_STATUS_STATUS_MCU, 1) //
								.build().wordOrder(WordOrder.LSWMSW), //
						m(GridConChannelId.PCS_IPU_3_STATUS_FILTER_CURRENT,
								new FloatDoublewordElement(33234).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_DC_LINK_POSITIVE_VOLTAGE,
								new FloatDoublewordElement(33236).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_DC_LINK_NEGATIVE_VOLTAGE,
								new FloatDoublewordElement(33238).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_DC_LINK_CURRENT,
								new FloatDoublewordElement(33240).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_DC_LINK_ACTIVE_POWER,
								new FloatDoublewordElement(33242).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_DC_LINK_UTILIZATION,
								new FloatDoublewordElement(33244).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_FAN_SPEED_MAX,
								new UnsignedDoublewordElement(33246).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_FAN_SPEED_MIN,
								new UnsignedDoublewordElement(33248).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_TEMPERATURE_IGBT_MAX,
								new FloatDoublewordElement(33250).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_TEMPERATURE_MCU_BOARD,
								new FloatDoublewordElement(33252).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_TEMPERATURE_GRID_CHOKE,
								new FloatDoublewordElement(33254).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_TEMPERATURE_INVERTER_CHOKE,
								new FloatDoublewordElement(33256).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_RESERVE_1,
								new FloatDoublewordElement(33258).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_RESERVE_2,
								new FloatDoublewordElement(33260).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_STATUS_RESERVE_3,
								new FloatDoublewordElement(33262).wordOrder(WordOrder.LSWMSW)) //
				), new FC3ReadRegistersTask(33264, Priority.LOW, // // IPU 4 state
						byteMap(new UnsignedDoublewordElement(33264)) //
								.mapByte(GridConChannelId.PCS_IPU_4_STATUS_STATUS_STATE_MACHINE, 0) //
								.mapByte(GridConChannelId.PCS_IPU_4_STATUS_STATUS_MCU, 1) //
								.build().wordOrder(WordOrder.LSWMSW), //
						m(GridConChannelId.PCS_IPU_4_STATUS_FILTER_CURRENT,
								new FloatDoublewordElement(33266).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_DC_LINK_POSITIVE_VOLTAGE,
								new FloatDoublewordElement(33268).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_DC_LINK_NEGATIVE_VOLTAGE,
								new FloatDoublewordElement(33270).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_DC_LINK_CURRENT,
								new FloatDoublewordElement(33272).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_DC_LINK_ACTIVE_POWER,
								new FloatDoublewordElement(33274).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_DC_LINK_UTILIZATION,
								new FloatDoublewordElement(33276).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_FAN_SPEED_MAX,
								new UnsignedDoublewordElement(33278).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_FAN_SPEED_MIN,
								new UnsignedDoublewordElement(33280).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_TEMPERATURE_IGBT_MAX,
								new FloatDoublewordElement(33282).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_TEMPERATURE_MCU_BOARD,
								new FloatDoublewordElement(33284).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_TEMPERATURE_GRID_CHOKE,
								new FloatDoublewordElement(33286).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_TEMPERATURE_INVERTER_CHOKE,
								new FloatDoublewordElement(33288).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_RESERVE_1,
								new FloatDoublewordElement(33290).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_RESERVE_2,
								new FloatDoublewordElement(33292).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_STATUS_RESERVE_3,
								new FloatDoublewordElement(33294).wordOrder(WordOrder.LSWMSW)) // TODO: is this float?
				), new FC3ReadRegistersTask(33488, Priority.LOW, // // IPU 1 measurements
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_VOLTAGE_STRING_A,
								new FloatDoublewordElement(33488).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_VOLTAGE_STRING_B,
								new FloatDoublewordElement(33490).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_VOLTAGE_STRING_C,
								new FloatDoublewordElement(33492).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_CURRENT_STRING_A,
								new FloatDoublewordElement(33494).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_CURRENT_STRING_B,
								new FloatDoublewordElement(33496).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_CURRENT_STRING_C,
								new FloatDoublewordElement(33498).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_POWER_STRING_A,
								new FloatDoublewordElement(33500).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_POWER_STRING_B,
								new FloatDoublewordElement(33502).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_POWER_STRING_C,
								new FloatDoublewordElement(33504).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_UTILIZATION_STRING_A,
								new FloatDoublewordElement(33506).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_UTILIZATION_STRING_B,
								new FloatDoublewordElement(33508).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_UTILIZATION_STRING_C,
								new FloatDoublewordElement(33510).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_ACCUMULATED_SUM_DC_CURRENT,
								new FloatDoublewordElement(33512).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_ACCUMULATED_DC_UTILIZATION,
								new FloatDoublewordElement(33514).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_RESERVE_1,
								new FloatDoublewordElement(33516).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_1_DC_DC_MEASUREMENTS_RESERVE_2,
								new FloatDoublewordElement(33518).wordOrder(WordOrder.LSWMSW)) //
				), new FC3ReadRegistersTask(33520, Priority.LOW, // IPU 2 measurements
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_VOLTAGE_STRING_A,
								new FloatDoublewordElement(33520).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_VOLTAGE_STRING_B,
								new FloatDoublewordElement(33522).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_VOLTAGE_STRING_C,
								new FloatDoublewordElement(33524).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_CURRENT_STRING_A,
								new FloatDoublewordElement(33526).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_CURRENT_STRING_B,
								new FloatDoublewordElement(33528).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_CURRENT_STRING_C,
								new FloatDoublewordElement(33530).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_POWER_STRING_A,
								new FloatDoublewordElement(33532).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_POWER_STRING_B,
								new FloatDoublewordElement(33534).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_POWER_STRING_C,
								new FloatDoublewordElement(33536).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_UTILIZATION_STRING_A,
								new FloatDoublewordElement(33538).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_UTILIZATION_STRING_B,
								new FloatDoublewordElement(33540).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_UTILIZATION_STRING_C,
								new FloatDoublewordElement(33542).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_ACCUMULATED_SUM_DC_CURRENT,
								new FloatDoublewordElement(33544).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_ACCUMULATED_DC_UTILIZATION,
								new FloatDoublewordElement(33546).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_RESERVE_1,
								new FloatDoublewordElement(33548).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_2_DC_DC_MEASUREMENTS_RESERVE_2,
								new FloatDoublewordElement(33550).wordOrder(WordOrder.LSWMSW)) //
				), new FC3ReadRegistersTask(33552, Priority.LOW, // IPU 3 measurements
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_VOLTAGE_STRING_A,
								new FloatDoublewordElement(33552).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_VOLTAGE_STRING_B,
								new FloatDoublewordElement(33554).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_VOLTAGE_STRING_C,
								new FloatDoublewordElement(33556).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_CURRENT_STRING_A,
								new FloatDoublewordElement(33558).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_CURRENT_STRING_B,
								new FloatDoublewordElement(33560).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_CURRENT_STRING_C,
								new FloatDoublewordElement(33562).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_POWER_STRING_A,
								new FloatDoublewordElement(33564).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_POWER_STRING_B,
								new FloatDoublewordElement(33566).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_POWER_STRING_C,
								new FloatDoublewordElement(33568).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_UTILIZATION_STRING_A,
								new FloatDoublewordElement(33570).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_UTILIZATION_STRING_B,
								new FloatDoublewordElement(33572).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_UTILIZATION_STRING_C,
								new FloatDoublewordElement(33574).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_ACCUMULATED_SUM_DC_CURRENT,
								new FloatDoublewordElement(33576).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_ACCUMULATED_DC_UTILIZATION,
								new FloatDoublewordElement(33578).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_RESERVE_1,
								new FloatDoublewordElement(33580).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_3_DC_DC_MEASUREMENTS_RESERVE_2,
								new FloatDoublewordElement(33582).wordOrder(WordOrder.LSWMSW)) //
				), new FC3ReadRegistersTask(33584, Priority.LOW, // IPU 4 measurements
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_VOLTAGE_STRING_A,
								new FloatDoublewordElement(33584).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_VOLTAGE_STRING_B,
								new FloatDoublewordElement(33586).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_VOLTAGE_STRING_C,
								new FloatDoublewordElement(33588).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_CURRENT_STRING_A,
								new FloatDoublewordElement(33590).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_CURRENT_STRING_B,
								new FloatDoublewordElement(33592).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_CURRENT_STRING_C,
								new FloatDoublewordElement(33594).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_POWER_STRING_A,
								new FloatDoublewordElement(33596).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_POWER_STRING_B,
								new FloatDoublewordElement(33598).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_POWER_STRING_C,
								new FloatDoublewordElement(33600).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_UTILIZATION_STRING_A,
								new FloatDoublewordElement(33602).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_UTILIZATION_STRING_B,
								new FloatDoublewordElement(33604).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_UTILIZATION_STRING_C,
								new FloatDoublewordElement(33606).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_ACCUMULATED_SUM_DC_CURRENT,
								new FloatDoublewordElement(33608).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_ACCUMULATED_DC_UTILIZATION,
								new FloatDoublewordElement(33610).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_RESERVE_1,
								new FloatDoublewordElement(33612).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_IPU_4_DC_DC_MEASUREMENTS_RESERVE_2,
								new FloatDoublewordElement(33614).wordOrder(WordOrder.LSWMSW)) //
				), new FC16WriteRegistersTask(32560, // Commands
						m(GridConChannelId.PCS_COMMAND_CONTROL_WORD,
								new UnsignedDoublewordElement(32560).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_COMMAND_ERROR_CODE_FEEDBACK,
								new UnsignedDoublewordElement(32562).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_U0,
								new FloatDoublewordElement(32564).wordOrder(WordOrder.LSWMSW)), // TODO Check word order
						m(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_F0,
								new FloatDoublewordElement(32566).wordOrder(WordOrder.LSWMSW)), // TODO Check word order
						m(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_Q_REF,
								new FloatDoublewordElement(32568).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_COMMAND_CONTROL_PARAMETER_P_REF,
								new FloatDoublewordElement(32570).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_COMMAND_TIME_SYNC_DATE,
								new UnsignedDoublewordElement(32572).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_COMMAND_TIME_SYNC_TIME,
								new UnsignedDoublewordElement(32574).wordOrder(WordOrder.LSWMSW)) //
				), new FC16WriteRegistersTask(32592, // Control parameters
						m(GridConChannelId.PCS_CONTROL_PARAMETER_U_Q_DROOP_MAIN,
								new FloatDoublewordElement(32592).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_U_Q_DROOP_T1_MAIN,
								new FloatDoublewordElement(32594).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_F_P_DRROP_MAIN,
								new FloatDoublewordElement(32596).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_F_P_DROOP_T1_MAIN,
								new FloatDoublewordElement(32598).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_Q_U_DROOP_MAIN,
								new FloatDoublewordElement(32600).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_Q_U_DEAD_BAND,
								new FloatDoublewordElement(32602).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_Q_LIMIT,
								new FloatDoublewordElement(32604).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_P_F_DROOP_MAIN,
								new FloatDoublewordElement(32606).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_P_F_DEAD_BAND,
								new FloatDoublewordElement(32608).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_P_U_DROOP,
								new FloatDoublewordElement(32610).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_P_U_DEAD_BAND,
								new FloatDoublewordElement(32612).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_P_U_MAX_CHARGE,
								new FloatDoublewordElement(32614).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_P_U_MAX_DISCHARGE,
								new FloatDoublewordElement(32616).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_P_CONTROL_MODE,
								new FloatDoublewordElement(32618).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_P_CONTROL_LIM_TWO,
								new FloatDoublewordElement(32620).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_PARAMETER_P_CONTROL_LIM_ONE,
								new FloatDoublewordElement(32622).wordOrder(WordOrder.LSWMSW)) //
				), new FC16WriteRegistersTask(32624, // IPU 1 control parameters
						m(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_DC_VOLTAGE_SETPOINT,
								new FloatDoublewordElement(32624).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_DC_CURRENT_SETPOINT,
								new FloatDoublewordElement(32626).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_U0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32628).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_F0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32630).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_Q_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32632).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_P_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32634).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_P_MAX_DISCHARGE,
								new FloatDoublewordElement(32636).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_1_PARAMETERS_P_MAX_CHARGE,
								new FloatDoublewordElement(32638).wordOrder(WordOrder.LSWMSW)) //
				), new FC16WriteRegistersTask(32656, // IPU 2 control parameters
						m(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_DC_VOLTAGE_SETPOINT,
								new FloatDoublewordElement(32656).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_DC_CURRENT_SETPOINT,
								new FloatDoublewordElement(32658).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_U0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32660).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_F0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32662).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_Q_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32664).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_P_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32666).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_P_MAX_DISCHARGE,
								new FloatDoublewordElement(32668).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_2_PARAMETERS_P_MAX_CHARGE,
								new FloatDoublewordElement(32670).wordOrder(WordOrder.LSWMSW)) //
				), new FC16WriteRegistersTask(32688, // IPU 3 control parameters
						m(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_DC_VOLTAGE_SETPOINT,
								new FloatDoublewordElement(32688).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_DC_CURRENT_SETPOINT,
								new FloatDoublewordElement(32690).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_U0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32692).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_F0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32694).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_Q_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32696).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_P_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32698).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_P_MAX_DISCHARGE,
								new FloatDoublewordElement(32700).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_3_PARAMETERS_P_MAX_CHARGE,
								new FloatDoublewordElement(32702).wordOrder(WordOrder.LSWMSW)) //
				), new FC16WriteRegistersTask(32720, // IPU 4 control parameters
						m(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_DC_VOLTAGE_SETPOINT,
								new FloatDoublewordElement(32720).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_A,
								new FloatDoublewordElement(32722).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_B,
								new FloatDoublewordElement(32724).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_C,
								new FloatDoublewordElement(32726).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_I_REF_STRING_A,
								new FloatDoublewordElement(32728).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_I_REF_STRING_B,
								new FloatDoublewordElement(32730).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_I_REF_STRING_C,
								new FloatDoublewordElement(32732).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_DC_DC_STRING_CONTROL_MODE,
								new FloatDoublewordElement(32734).wordOrder(WordOrder.LSWMSW)) //
				)
				// Mirror values to check
				, new FC3ReadRegistersTask(32880, Priority.LOW, // Commands
						m(GridConChannelId.MIRROR_PCS_COMMAND_CONTROL_WORD,
								new UnsignedDoublewordElement(32880).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_COMMAND_ERROR_CODE_FEEDBACK,
								new UnsignedDoublewordElement(32882).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_COMMAND_CONTROL_PARAMETER_U0,
								new FloatDoublewordElement(32884).wordOrder(WordOrder.LSWMSW)), // TODO Check word order
						m(GridConChannelId.MIRROR_PCS_COMMAND_CONTROL_PARAMETER_F0,
								new FloatDoublewordElement(32886).wordOrder(WordOrder.LSWMSW)), // TODO Check word order
						m(GridConChannelId.MIRROR_PCS_COMMAND_CONTROL_PARAMETER_Q_REF,
								new FloatDoublewordElement(32888).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_COMMAND_CONTROL_PARAMETER_P_REFERENCE,
								new FloatDoublewordElement(32890).wordOrder(WordOrder.LSWMSW)) //
				),
				new FC3ReadRegistersTask(32912, Priority.LOW,
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_U_Q_DROOP_MAIN,
								new FloatDoublewordElement(32912).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_U_Q_DROOP_T1_MAIN,
								new FloatDoublewordElement(32914).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_F_P_DRROP_MAIN,
								new FloatDoublewordElement(32916).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_F_P_DROOP_T1_MAIN,
								new FloatDoublewordElement(32918).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_Q_U_DROOP_MAIN,
								new FloatDoublewordElement(32920).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_Q_U_DEAD_BAND,
								new FloatDoublewordElement(32922).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_Q_LIMIT,
								new FloatDoublewordElement(32924).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_P_F_DROOP_MAIN,
								new FloatDoublewordElement(32926).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_P_F_DEAD_BAND,
								new FloatDoublewordElement(32928).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_PARAMETER_P_U_DROOP,
								new FloatDoublewordElement(32930).wordOrder(WordOrder.LSWMSW)) //
				),
				new FC3ReadRegistersTask(32944, Priority.LOW,
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_1_PARAMETERS_DC_VOLTAGE_SETPOINT,
								new FloatDoublewordElement(32944).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_1_PARAMETERS_DC_CURRENT_SETPOINT,
								new FloatDoublewordElement(32946).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_1_PARAMETERS_U0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32948).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_1_PARAMETERS_F0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32950).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_1_PARAMETERS_Q_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32952).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_1_PARAMETERS_P_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32954).wordOrder(WordOrder.LSWMSW)) //
				),
				new FC3ReadRegistersTask(32976, Priority.LOW,
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_2_PARAMETERS_DC_VOLTAGE_SETPOINT,
								new FloatDoublewordElement(32976).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_2_PARAMETERS_DC_CURRENT_SETPOINT,
								new FloatDoublewordElement(32978).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_2_PARAMETERS_U0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32980).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_2_PARAMETERS_F0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32982).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_2_PARAMETERS_Q_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32984).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_2_PARAMETERS_P_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(32986).wordOrder(WordOrder.LSWMSW)) //
				),
				new FC3ReadRegistersTask(33008, Priority.LOW,
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_3_PARAMETERS_DC_VOLTAGE_SETPOINT,
								new FloatDoublewordElement(33008).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_3_PARAMETERS_DC_CURRENT_SETPOINT,
								new FloatDoublewordElement(33010).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_3_PARAMETERS_U0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(33012).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_3_PARAMETERS_F0_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(33014).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_3_PARAMETERS_Q_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(33016).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_3_PARAMETERS_P_REF_OFFSET_TO_CCU_VALUE,
								new FloatDoublewordElement(33018).wordOrder(WordOrder.LSWMSW)) //
				),
				new FC3ReadRegistersTask(33040, Priority.LOW,
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_DC_VOLTAGE_SETPOINT,
								new FloatDoublewordElement(33040).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_A,
								new FloatDoublewordElement(33042).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_B,
								new FloatDoublewordElement(33044).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_WEIGHT_STRING_C,
								new FloatDoublewordElement(33046).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_I_REF_STRING_A,
								new FloatDoublewordElement(33048).wordOrder(WordOrder.LSWMSW)), //
						m(GridConChannelId.MIRROR_PCS_CONTROL_IPU_4_DC_DC_CONVERTER_PARAMETERS_I_REF_STRING_B,
								new FloatDoublewordElement(33050).wordOrder(WordOrder.LSWMSW)) //
				));
		
		// Calculate Total Active Power
		FloatReadChannel ap1 = this.channel(GridConChannelId.PCS_IPU_1_STATUS_DC_LINK_ACTIVE_POWER);
		FloatReadChannel ap2 = this.channel(GridConChannelId.PCS_IPU_2_STATUS_DC_LINK_ACTIVE_POWER);
		FloatReadChannel ap3 = this.channel(GridConChannelId.PCS_IPU_3_STATUS_DC_LINK_ACTIVE_POWER);
		final Consumer<Value<Float>> calculateActivePower = ignoreValue -> {
			float ipu1 = ap1.getNextValue().orElse(0f);
			float ipu2 = ap2.getNextValue().orElse(0f);
			float ipu3 = ap3.getNextValue().orElse(0f);
			this.getActivePower().setNextValue((ipu1 + ipu2 + ipu3) * -1);
		};
		ap1.onSetNextValue(calculateActivePower);
		ap2.onSetNextValue(calculateActivePower);
		ap3.onSetNextValue(calculateActivePower);
		
		return protocol;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return defineModbusProtocol(0);
	}
}
