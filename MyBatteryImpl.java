package org.matsim.contrib.ev.fleet;

public class MyBatteryImpl implements Battery {
	
	private Battery delegate;
	private double estimatedSoc;

	public MyBatteryImpl(Battery battery){
		this.delegate = battery;
		this.estimatedSoc = battery.getSoc();
	}

	@Override
	public double getCapacity() {
		return this.delegate.getCapacity();
	}

	@Override
	public double getSoc() {
		return this.delegate.getSoc();
	}

	@Override
	public void setSoc(double soc) {
		this.delegate.setSoc(soc);
	}
	
	public double getEstimatedSoc() {
		return this.estimatedSoc;
	}
	
	public double setEstimatedSoc(double estimatedSoc) {
		return this.estimatedSoc = estimatedSoc;
	}
	
	public void changeestimatedSoc(double delta) {
		this.estimatedSoc += delta;
	}

}