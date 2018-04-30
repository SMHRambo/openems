package io.openems.core.utilities.power.symmetric;

public class MaxCosPhiLimitation extends Limitation {
	// MOVED TO OSGi
	//	private Geometry polygon;
	//	private Double cosPhi;
	//
	//	public MaxCosPhiLimitation(SymmetricPower power) {
	//		super(power);
	//	}
	//
	//	public void setMaxCosPhi(Double cosPhi) {
	//		if (cosPhi != this.cosPhi) {
	//			if (cosPhi != null) {
	//				Long maxApparentPower = this.power.getMaxApparentPower();
	//				GeometryFactory factory = SymmetricPowerImpl.getFactory();
	//				double m = Math.tan(Math.acos(cosPhi));
	//				double y = m * maxApparentPower;
	//				Coordinate[] coordinates = new Coordinate[] { new Coordinate(ZERO), new Coordinate(maxApparentPower, y),
	//						new Coordinate(maxApparentPower, y * -1), new Coordinate(ZERO),
	//						new Coordinate(maxApparentPower * -1, y * -1), new Coordinate(maxApparentPower * -1, y),
	//						new Coordinate(ZERO) };
	//				polygon = factory.createPolygon(coordinates);
	//			} else {
	//				polygon = null;
	//			}
	//			this.cosPhi = cosPhi;
	//			notifyListeners();
	//		}
	//	}
	//
	//	@Override
	//	public Geometry applyLimit(Geometry geometry) throws PowerException {
	//		if (this.polygon != null) {
	//			Geometry newGeometry = geometry.intersection(polygon);
	//			if (newGeometry.isEmpty()) {
	//				throw new PowerException(
	//						"The CosPhi limitation is too small! There needs to be at least one point after the limitation.");
	//			}
	//			return newGeometry;
	//		}
	//		return geometry;
	//	}
	//
	//	@Override
	//	public String toString() {
	//		return "The Power has to be between CosPhi "+cosPhi+" inductive and capacitive.";
	//	}

}