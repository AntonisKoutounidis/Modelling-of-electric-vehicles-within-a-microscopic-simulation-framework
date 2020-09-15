/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** *
 */

package org.matsim.contrib.ev.fleet;

import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.ev.EvUnits;
import org.matsim.contrib.ev.charging.ChargingPower;
import org.matsim.contrib.ev.discharging.AuxEnergyConsumption;
import org.matsim.contrib.ev.discharging.DriveEnergyConsumption;

import com.google.common.collect.ImmutableList;

public class MyElectricVehicleImpl implements ElectricVehicle {
	
	private Battery battery;
	private ElectricVehicle delegate;
	private final double ChargingBehavior;
	private final boolean PrivateParking;
	private final boolean WorkParking;
	private int PrivateParkingRate = 11;
	private int WorkParkingRate = 10;
	private String VehicleSize;
	private double ChargeUpTo;
	
	
	public static ElectricVehicle create(ElectricVehicleSpecification vehicleSpecification,
			DriveEnergyConsumption.Factory driveFactory, AuxEnergyConsumption.Factory auxFactory,
			ChargingPower.Factory chargingFactory) {
		return new MyElectricVehicleImpl(vehicleSpecification, driveFactory, auxFactory, chargingFactory);
	}
	
	public MyElectricVehicleImpl(ElectricVehicleSpecification vehicleSpecification,
			DriveEnergyConsumption.Factory driveFactory, AuxEnergyConsumption.Factory auxFactory,
			ChargingPower.Factory chargingFactory) {
		this.delegate = ElectricVehicleImpl.create(vehicleSpecification, driveFactory, auxFactory, chargingFactory);
		this.battery = new MyBatteryImpl(delegate.getBattery());
		Random r = new Random();
		this.ChargeUpTo = this.battery.getCapacity();
		this.ChargingBehavior = EvUnits.kWh_to_J((r.nextInt(41)+30)/2);
		if(r.nextInt(100)<=PrivateParkingRate) {
			this.PrivateParking = true;
		}else {
			this.PrivateParking = false;
		}
		if(r.nextInt(100)<=WorkParkingRate) {
			this.WorkParking = true;
		}else {
			this.WorkParking = false;
		}
		if(this.battery.getCapacity() == 40) {
			this.VehicleSize = "small";
		}
		else if(this.battery.getCapacity() == 50) {
			this.VehicleSize = "medium";
		}
		else {
			this.VehicleSize = "large";
		}
		//this.battery.changeSoc(EvUnits.kWh_to_J(50));
	}
		
	
	public void setBattery(Battery battery) {
		this.battery = battery;
	}
	@Override
	public Battery getBattery() {
		return this.battery;
	}
	
	@Override
	public Id<ElectricVehicle> getId() {
		return this.delegate.getId();
	}
	@Override
	public DriveEnergyConsumption getDriveEnergyConsumption() {
		return this.delegate.getDriveEnergyConsumption();
	}
	@Override
	public AuxEnergyConsumption getAuxEnergyConsumption() {
		return this.delegate.getAuxEnergyConsumption();
	}
	@Override
	public ChargingPower getChargingPower() {
		return this.delegate.getChargingPower();
	}

	@Override
	public String getVehicleType() {
		return this.delegate.getVehicleType();
	}
	@Override
	public ImmutableList<String> getChargerTypes() {
		return this.delegate.getChargerTypes();
	}
	
	public double getChargingBehavior() {
		return this.ChargingBehavior;
	}
	
	public boolean getPrivateParking() {
		return this.PrivateParking;
	}
	
	public boolean getWorkParking() {
		return this.WorkParking;
	}
	public String getVehicleSize() {
		return this.VehicleSize;
	}
	public void setChargeUpTo(double ChargeUpTo) {
		this.ChargeUpTo = ChargeUpTo;
	}
	public double getChargeUpTo() {
		return this.ChargeUpTo;
	}
}