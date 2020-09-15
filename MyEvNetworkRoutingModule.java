/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2015 by the members listed in the COPYING,        *
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
 * *********************************************************************** */
package org.matsim.contrib.ev.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;


import org.apache.commons.math3.distribution.NormalDistribution;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.ev.EvConfigGroup;
import org.matsim.contrib.ev.charging.ChargingPower;
import org.matsim.contrib.ev.charging.VehicleChargingHandler;
import org.matsim.contrib.ev.discharging.AuxEnergyConsumption;
import org.matsim.contrib.ev.discharging.DriveEnergyConsumption;
import org.matsim.contrib.ev.example.CONSTANT;
import org.matsim.contrib.ev.fleet.ElectricFleet;
import org.matsim.contrib.ev.fleet.ElectricFleetSpecification;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.contrib.ev.fleet.ElectricVehicleSpecification;
import org.matsim.contrib.ev.fleet.MyBatteryImpl;
import org.matsim.contrib.ev.fleet.MyElectricFleets;
import org.matsim.contrib.ev.fleet.MyElectricVehicleImpl;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargerSpecification;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.contrib.util.StraightLineKnnFinder;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.LinkWrapperFacility;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.facilities.Facility;

/**
 * This network Routing module adds stages for re-charging into the Route.
 * This wraps a "computer science" {@link LeastCostPathCalculator}, which routes from a node to another node, into something that
 * routes from a {@link Facility} to another {@link Facility}, as we need in MATSim.
 *
 * @author jfbischoff
 */

public final class MyEvNetworkRoutingModule implements RoutingModule {

	private final String mode;

	private final Network network;
	private final RoutingModule delegate;
	private final ElectricFleetSpecification electricFleet;
	private final ChargingInfrastructureSpecification chargingInfrastructureSpecification;
	private final Random random = MatsimRandom.getLocalInstance();
	private final TravelTime travelTime;
	private final DriveEnergyConsumption.Factory driveConsumptionFactory;
	private final AuxEnergyConsumption.Factory auxConsumptionFactory;
	private final String stageActivityModePrefix;
	private final String vehicleSuffix;
	private final EvConfigGroup evConfigGroup;
	private final RoutingModule walkRouter;
	private final RoutingModule fastwalk;

	//NEW
	private ElectricFleet fleet;

	public MyEvNetworkRoutingModule(final String mode, final Network network, RoutingModule delegate,
			ElectricFleetSpecification electricFleet,
			ChargingInfrastructureSpecification chargingInfrastructureSpecification, TravelTime travelTime,
			DriveEnergyConsumption.Factory driveConsumptionFactory, AuxEnergyConsumption.Factory auxConsumptionFactory,
			EvConfigGroup evConfigGroup, ChargingPower.Factory chargingFactory, RoutingModule walkRouter, RoutingModule fastwalk) {
		this.walkRouter = walkRouter;
		this.fastwalk = fastwalk;
		this.travelTime = travelTime;
		Gbl.assertNotNull(network);
		this.delegate = delegate;
		this.network = network;
		this.mode = mode;
		this.electricFleet = electricFleet;
		this.chargingInfrastructureSpecification = chargingInfrastructureSpecification;
		this.driveConsumptionFactory = driveConsumptionFactory;
		this.auxConsumptionFactory = auxConsumptionFactory;
		stageActivityModePrefix = mode + VehicleChargingHandler.CHARGING_IDENTIFIER;
		this.evConfigGroup = evConfigGroup;
		this.vehicleSuffix = mode.equals(TransportMode.car) ? "" : "_" + mode;
		
		//NEW
		this.fleet = MyElectricFleets.createDefaultFleet(electricFleet,driveConsumptionFactory,  auxConsumptionFactory, chargingFactory);
	}

	@Override
	public List<? extends PlanElement> calcRoute(final Facility fromFacility, final Facility toFacility,
			final double departureTime, final Person person) {

		List<? extends PlanElement> basicRoute = delegate.calcRoute(fromFacility, toFacility, departureTime, person);
		Id<ElectricVehicle> evId = Id.create(person.getId() + vehicleSuffix, ElectricVehicle.class);
		
		//NEW
		Activity findAct = null;
		Activity firstAct = (Activity) person.getSelectedPlan().getPlanElements().get(0);
		//Leg	findLeg = null;
		Class<? extends PlanElement> cls = null;
		boolean walkLeg = false;
		Leg	findwalkLeg = null;
		double lastDeparture = 0;
		//person.getAttributes().getAttribute("subpopulation").toString().contains("ElectricSubpopulation");
		if (!electricFleet.getVehicleSpecifications().containsKey(evId)) {
			return basicRoute;
		} else {
			boolean lastDepartureInfinity = false;
			boolean flag = false;
			int PlanSize = person.getSelectedPlan().getPlanElements().size();
			int i = 0;
			//for (PlanElement planElement : person.getSelectedPlan().getPlanElements()) {
			for (i = 0; i <= PlanSize;i++){
				//if(i == PlanSize - 1) {
					//findAct = (Activity) person.getSelectedPlan().getPlanElements().get(i);
					//lastDepartureInfinity = true;
					//break;
				//}
				cls = person.getSelectedPlan().getPlanElements().get(i).getClass();
				//cls = planElement.getClass();
				if (cls.getName() == "org.matsim.core.population.ActivityImpl") {
					findAct = (Activity) person.getSelectedPlan().getPlanElements().get(i);
					//findAct = (Activity) planElement;
					if(flag == true) {
						if (findAct.getEndTime().toString()=="OptionalTime[UNDEFINED]" || i == PlanSize-1){
							lastDepartureInfinity = true;
							break;
						}
						else {
							lastDeparture = findAct.getEndTime().seconds();
							break;
						}
					}
					if (findAct.getEndTime().toString()!="OptionalTime[UNDEFINED]"){
						if (findAct.getEndTime().seconds() == departureTime) {
							//findAct = (Activity) person.getSelectedPlan().getPlanElements().get(i+2);
							flag = true;
							//if (findAct.getEndTime().toString()!="OptionalTime[UNDEFINED]"){
								//lastDeparture = findAct.getEndTime().seconds();
								//break;
							//}
						}
					}
				}
			}
			if (i >=4) {
				cls = person.getSelectedPlan().getPlanElements().get(i-3).getClass();
				if (cls.getName() == "org.matsim.core.population.LegImpl") {
					findwalkLeg =(Leg) person.getSelectedPlan().getPlanElements().get(i-3);
					if (findwalkLeg.getMode().contains("walk")) {
						walkLeg = true;
					}
				}
			}
			if (walkLeg == true) {
				List<PlanElement> stagedRoute = new ArrayList<>();
				Link startlink = network.getLinks().get(findwalkLeg.getRoute().getStartLinkId());
				Link endlink = network.getLinks().get(findwalkLeg.getRoute().getEndLinkId());
				Facility startFacility = new LinkWrapperFacility(startlink);
				Facility endFacility = new LinkWrapperFacility(endlink);
				List<? extends PlanElement> routeSegment = null;
				if (findwalkLeg.getMode().contains("fast")){
					routeSegment = fastwalk.calcRoute(fromFacility, startFacility, departureTime, person);
				}else {
					routeSegment = walkRouter.calcRoute(fromFacility, startFacility, departureTime, person);
				}
				Leg createLeg = (Leg) routeSegment.get(0);
				//createLeg.setTravelTime(findwalkLeg.getTravelTime());
				stagedRoute.add(createLeg);
				//createLeg.setMode("fast_walk");
				//stagedRoute.addAll(walkRouter.calcRoute(endFacility, startFacility, departureTime, person));
				//List<? extends PlanElement> routeSegment0 = walkRouter.calcRoute(endFacility, startFacility, departureTime, person);
				//Leg legtime = (Leg) routeSegment0.get(0);
				//legtime.setTravelTime(findwalkLeg.getTravelTime());
				//stagedRoute.add(legtime);
				Activity carInteraction = PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(startlink.getCoord(),
						startlink.getId(), "car");
				stagedRoute.add(carInteraction);
				stagedRoute.addAll(delegate.calcRoute(startFacility, toFacility, departureTime + findwalkLeg.getTravelTime(), person));
				return stagedRoute;
			}
//				cls = person.getSelectedPlan().getPlanElements().get(i).getClass();
//				if (cls.getName() == "org.matsim.core.population.ActivityImpl") {
//					findAct = (Activity) person.getSelectedPlan().getPlanElements().get(i);
//					if (findAct.getEndTime().seconds() == departureTime) {
//						cls = person.getSelectedPlan().getPlanElements().get(i+1).getClass();
//						if (cls.getName() == "org.matsim.core.population.ActivityImpl") {
//							findAct = (Activity) person.getSelectedPlan().getPlanElements().get(i+1);
//							lastDeparture = findAct.getEndTime().seconds();
//						}
//						else {
//							findLeg = (Leg) person.getSelectedPlan().getPlanElements().get(i+1);
//							lastDeparture = findLeg.getDepartureTime();
//						}
//						break;
//					}
//				}
//				else {
//					findLeg = (Leg) person.getSelectedPlan().getPlanElements().get(i);
//					if (findLeg.getDepartureTime() == departureTime) {
//						cls = person.getSelectedPlan().getPlanElements().get(i+1).getClass();
//						if (cls.getName() == "org.matsim.core.population.ActivityImpl") {
//							findAct = (Activity) person.getSelectedPlan().getPlanElements().get(i+1);
//							lastDeparture = findAct.getEndTime().seconds();
//						}
//						else {
//							findLeg = (Leg) person.getSelectedPlan().getPlanElements().get(i+1);
//							lastDeparture = findLeg.getDepartureTime();
//						}
//						break;
//					}
//				
			//findAct.getCoord().
			ElectricVehicle vehicle = fleet.getElectricVehicles().get(evId);
			if (departureTime == firstAct.getEndTime().seconds()) {
				vehicle.getBattery().setEstimatedSoc(vehicle.getBattery().getSoc());
			}
			MyBatteryImpl battery = (MyBatteryImpl) vehicle.getBattery();
			Leg basicLeg = (Leg)basicRoute.get(0);
			ElectricVehicleSpecification ev = electricFleet.getVehicleSpecifications().get(evId);
			Map<Link, Double> estimatedEnergyConsumption = estimateConsumption(electricFleet.getVehicleSpecifications().get(evId), basicLeg);
			double estimatedOverallConsumption = estimatedEnergyConsumption.values()
					.stream()
					.mapToDouble(Number::doubleValue)
					.sum();
			double estimatedSoc = battery.getEstimatedSoc();
			Integer key = 0;
			Integer value = 0;
			//double ChargingBehavior = vehicle.getChargingBehavior();
			//double capacity = ev.getBatteryCapacity() * (0.8 + random.nextDouble() * 0.18);
			double numberOfStops = Math.floor(estimatedOverallConsumption/estimatedSoc);
			double socRate = (estimatedSoc/vehicle.getBattery().getCapacity())*100;
			Random r = new Random();
			for (Map.Entry<Integer, Integer> entry : CONSTANT.PublicPlugRate.entrySet()) {
			    key = entry.getKey();
			    value = entry.getValue();
			    if(socRate <= key) {
			    	break;
			    }
			}
			if (numberOfStops < 1 && r.nextDouble()*100 > value) {
				return basicRoute;
			} else if(numberOfStops > 1){
				if (isCoordInsidePolygon(findAct.getCoord(),getVertices()) == false) {
					List<Link> stopLocations = new ArrayList<>();
					double currentConsumption = 0;
					for (Map.Entry<Link, Double> e : estimatedEnergyConsumption.entrySet()) {
						currentConsumption += e.getValue();
						if (currentConsumption > estimatedSoc) {
							stopLocations.add(e.getKey());
							currentConsumption = 0;
						}
					}
					List<PlanElement> stagedRoute = new ArrayList<>();
					Facility lastFrom = fromFacility;
					double lastArrivaltime = departureTime;
					for (Link stopLocation : stopLocations) {
	
						StraightLineKnnFinder<Link, ChargerSpecification> straightLineKnnFinder = new StraightLineKnnFinder<>(
								2, l -> l, s -> network.getLinks().get(s.getLinkId()));
						List<ChargerSpecification> nearestChargers = straightLineKnnFinder.findNearest(stopLocation,
								chargingInfrastructureSpecification.getChargerSpecifications()
										.values()
										.stream()
										.filter(charger -> charger.getId().toString().length() < 10));
						ChargerSpecification selectedCharger = nearestChargers.get(random.nextInt(1));
						Link selectedChargerLink = network.getLinks().get(selectedCharger.getLinkId());
						Facility nexttoFacility = new LinkWrapperFacility(selectedChargerLink);
						if (nexttoFacility.getLinkId().equals(lastFrom.getLinkId())) {
							continue;
						}
						List<? extends PlanElement> routeSegment = delegate.calcRoute(lastFrom, nexttoFacility,
								lastArrivaltime, person);
						Leg lastLeg = (Leg)routeSegment.get(0);
						lastArrivaltime = lastLeg.getDepartureTime() + lastLeg.getTravelTime();
						stagedRoute.add(lastLeg);
						Activity chargeAct = PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(selectedChargerLink.getCoord(),
								selectedChargerLink.getId(), stageActivityModePrefix);
						double afterConsumptionSoc = battery.getSoc()-estimatedOverallConsumption;
						double maxPowerEstimate = Math.min(selectedCharger.getPlugPower(), ev.getBatteryCapacity() / 3.6);
						double estimatedChargingPower = (evConfigGroup.getMinimumChargeTime()*maxPowerEstimate)/1.5;
						vehicle.setChargeUpTo(Math.min(estimatedChargingPower+afterConsumptionSoc,ev.getBatteryCapacity()));
						vehicle.getBattery().setEstimatedSoc(vehicle.getChargeUpTo());
						chargeAct.setMaximumDuration(evConfigGroup.getMinimumChargeTime());
						lastArrivaltime += chargeAct.getMaximumDuration().seconds();
						stagedRoute.add(chargeAct);
						lastFrom = nexttoFacility;
					}
					stagedRoute.addAll(delegate.calcRoute(lastFrom, toFacility, lastArrivaltime, person));
					//battery.setSoc(battery.getSoc()-estimatedOverallConsumption);
					return stagedRoute;
				}else{
					List<PlanElement> stagedRoute = new ArrayList<>();
					Facility lastFrom = fromFacility;
					double lastArrivaltime = departureTime;
					Link toFacilityLink = network.getLinks().get(toFacility.getLinkId());
					StraightLineKnnFinder<Link, ChargerSpecification> straightLineKnnFinder = new StraightLineKnnFinder<>(
							2, l -> l, s -> network.getLinks().get(s.getLinkId()));
//					List<ChargerSpecification> nearestChargers = straightLineKnnFinder.findNearest(toFacilityLink,
//							chargingInfrastructureSpecification.getChargerSpecifications()
//									.values()
//									.stream()
//									.filter(charger -> ev.getChargerTypes().contains(charger.getChargerType())));
					List<ChargerSpecification> nearestChargers = straightLineKnnFinder.findNearest(toFacilityLink,
							chargingInfrastructureSpecification.getChargerSpecifications()
									.values()
									.stream()
									.filter(charger -> charger.getId().toString().length() < 10));
					// Select closest charger = no stohasticity
					ChargerSpecification selectedCharger = nearestChargers.get(0);
					//ChargerSpecification selectedCharger = nearestChargers.get(random.nextInt(1));
					Link selectedChargerLink = network.getLinks().get(selectedCharger.getLinkId());
					Facility nexttoFacility = new LinkWrapperFacility(selectedChargerLink);
					//if (nexttoFacility.getLinkId().equals(lastFrom.getLinkId())) {
					//	continue;
					//}
					List<? extends PlanElement> routeSegment = delegate.calcRoute(lastFrom, nexttoFacility,
							lastArrivaltime, person);
					Leg lastLeg = (Leg)routeSegment.get(0);
					lastArrivaltime = lastLeg.getDepartureTime() + lastLeg.getTravelTime();
					stagedRoute.add(lastLeg);
					Activity chargeAct = PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(selectedChargerLink.getCoord(),
							selectedChargerLink.getId(), stageActivityModePrefix);
					double maxPowerEstimate = Math.min(selectedCharger.getPlugPower(), ev.getBatteryCapacity()*0.2 / 3.6);
					double afterConsumptionSoc = battery.getSoc()-estimatedOverallConsumption;
					
					lastFrom = nexttoFacility;
					List<? extends PlanElement> routeSegment1 = walkRouter.calcRoute(lastFrom, toFacility, lastArrivaltime, person);
					Leg lastLeg1 = (Leg)routeSegment1.get(0);
					//stagedRoute.add(lastLeg1);
					double walkingtime =  lastLeg1.getTravelTime();
					//stagedRoute.addAll(walkRouter.calcRoute(lastFrom, toFacility, lastArrivaltime, person));
					double maxChargingTime = 0;
					double actduration = 0;
					if (findAct.getEndTime().toString()=="OptionalTime[UNDEFINED]") {
						findAct.setEndTime(3600*24);
						actduration	= findAct.getEndTime().seconds() - lastArrivaltime;
						maxChargingTime = Math.min(actduration+walkingtime,evConfigGroup.getMaximumChargeTime());
					}else if(findAct.getEndTime().seconds() == 97200){						
						findAct.setEndTime(3600*24);
						actduration	= findAct.getEndTime().seconds() - lastArrivaltime;
						maxChargingTime = Math.min(actduration+walkingtime,evConfigGroup.getMaximumChargeTime());
					}else
						actduration	= findAct.getEndTime().seconds() - lastArrivaltime;
						maxChargingTime = Math.min(actduration+walkingtime,evConfigGroup.getMaximumChargeTime());
					double estimatedChargingPower = (maxChargingTime*maxPowerEstimate)/1.5;
					vehicle.setChargeUpTo(Math.min(estimatedChargingPower+afterConsumptionSoc,ev.getBatteryCapacity()));
					vehicle.getBattery().setEstimatedSoc(vehicle.getChargeUpTo());
					if(estimatedChargingPower+afterConsumptionSoc >= ev.getBatteryCapacity()) {
						estimatedChargingPower = ev.getBatteryCapacity() - afterConsumptionSoc;
					}
					double estimatedChargingTime = (estimatedChargingPower)*1.5 / maxPowerEstimate;
					if(estimatedChargingTime < evConfigGroup.getMinimumChargeTime()) {
						return basicRoute;
					}
					chargeAct.setMaximumDuration(estimatedChargingTime);
					stagedRoute.add(chargeAct);
					stagedRoute.add(lastLeg1);
					return stagedRoute;
				}

			}
			else {
				Id<Charger> charIdHome = Id.create(person.getId() + "home", Charger.class);
				Id<Charger> charIdWork = Id.create(person.getId() + "work", Charger.class);
				ChargerSpecification homeCharger =  chargingInfrastructureSpecification.getChargerSpecifications().get(charIdHome);
				ChargerSpecification workCharger =  chargingInfrastructureSpecification.getChargerSpecifications().get(charIdWork);
				Integer homekey = 0;
				Integer homevalue = 0;
				if(chargingInfrastructureSpecification.getChargerSpecifications().containsKey(charIdHome) && findAct.getType()=="home") {
					for (Map.Entry<Integer, Integer> entry : CONSTANT.HomePlugRate.entrySet()) {
					    homekey = entry.getKey();
					    homevalue = entry.getValue();
					    if(socRate <= homekey) {
					    	break;
					    }
					}
			    	if(r.nextDouble()*100 <= homevalue) {
			    		List<PlanElement> stagedRoute = new ArrayList<>();
						double lastArrivaltime = departureTime;
						Facility lastFrom = fromFacility;
						Link selectedChargerLink = network.getLinks().get(homeCharger.getLinkId());
						Facility HomeChargingFacility = new LinkWrapperFacility(selectedChargerLink);
						List<? extends PlanElement> routeSegment = delegate.calcRoute(lastFrom, HomeChargingFacility,
								lastArrivaltime, person);
						Leg lastLeg = (Leg)routeSegment.get(0);
						stagedRoute.add(lastLeg);
						lastArrivaltime = lastLeg.getDepartureTime() + lastLeg.getTravelTime();
						Activity homeChargeAct = PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(selectedChargerLink.getCoord(),
								selectedChargerLink.getId(), "home charging");
						double maxPowerEstimate = Math.min(homeCharger.getPlugPower(), ev.getBatteryCapacity()*0.2 / 3.6);
						double afterConsumptionSoc = battery.getSoc()-estimatedOverallConsumption;
						lastFrom = HomeChargingFacility;
						List<? extends PlanElement> routeSegment1 = fastwalk.calcRoute(HomeChargingFacility, toFacility, lastArrivaltime, person);
						Leg lastLeg1 = (Leg)routeSegment1.get(0);
						//stagedRoute.add(lastLeg1);
						//double walkingtime =  lastLeg1.getTravelTime();
						
						double maxChargingTime = 0;
						double actduration = 0;
						if (findAct.getEndTime().toString()=="OptionalTime[UNDEFINED]") {
							findAct.setEndTime(3600*30);
							actduration	= findAct.getEndTime().seconds()- lastArrivaltime;
							maxChargingTime = actduration;
						}else if(findAct.getEndTime().seconds() == 97200){						
							findAct.setEndTime(3600*30);
							actduration	= findAct.getEndTime().seconds()- lastArrivaltime;
							maxChargingTime = actduration;
						}else
							actduration	= findAct.getEndTime().seconds()- lastArrivaltime;
							maxChargingTime = actduration;
						double estimatedChargingPower = (maxChargingTime*maxPowerEstimate)/1.5;
						vehicle.setChargeUpTo(Math.min(estimatedChargingPower+afterConsumptionSoc,ev.getBatteryCapacity()));
						vehicle.getBattery().setEstimatedSoc(vehicle.getChargeUpTo());
						if(estimatedChargingPower+afterConsumptionSoc >= ev.getBatteryCapacity()) {
							estimatedChargingPower = ev.getBatteryCapacity() - afterConsumptionSoc;
						}
						double estimatedChargingTime = (estimatedChargingPower)*1.5 / maxPowerEstimate;
						homeChargeAct.setMaximumDuration(estimatedChargingTime);
						//lastArrivaltime += chargeAct.getMaximumDuration().seconds();
						//lastArrivaltime += lastDeparture;
						//chargeAct.setMaximumDuration(chargeAct.getMaximumDuration().seconds());
						stagedRoute.add(homeChargeAct);
						stagedRoute.add(lastLeg1);
						vehicle.setChargeUpTo(ev.getBatteryCapacity());
						return stagedRoute;
			    	}else {
			    		return basicRoute;
			    	}
					 
					
				}else if (chargingInfrastructureSpecification.getChargerSpecifications().containsKey(charIdWork) && findAct.getType()=="work") {
					Integer workkey = 0;
				    Integer workvalue = 0;
					for (Map.Entry<Integer, Integer> entry : CONSTANT.WorkPlugRate.entrySet()) {
					    workkey = entry.getKey();
					    workvalue = entry.getValue();
					    if(socRate <= workkey) {
					    	break;
					    }
					}   
			    	if(r.nextDouble()*100 <= workvalue) {
			    		List<PlanElement> stagedRoute = new ArrayList<>();
						double lastArrivaltime = departureTime;
						Facility lastFrom = fromFacility;
						Link selectedChargerLink = network.getLinks().get(workCharger.getLinkId());
						Facility WorkChargingFacility = new LinkWrapperFacility(selectedChargerLink);
						List<? extends PlanElement> routeSegment = delegate.calcRoute(lastFrom, WorkChargingFacility,
								lastArrivaltime, person);
						Leg lastLeg = (Leg)routeSegment.get(0);
						stagedRoute.add(lastLeg);
						lastArrivaltime = lastLeg.getDepartureTime() + lastLeg.getTravelTime();
						Activity workChargeAct = PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(selectedChargerLink.getCoord(),
								selectedChargerLink.getId(), "work charging");
						double maxPowerEstimate = Math.min(workCharger.getPlugPower(), ev.getBatteryCapacity()*0.2 / 3.6);
						double afterConsumptionSoc = battery.getSoc()-estimatedOverallConsumption;
						lastFrom = WorkChargingFacility;
						List<? extends PlanElement> routeSegment1 = fastwalk.calcRoute(WorkChargingFacility, toFacility, lastArrivaltime, person);
						Leg lastLeg1 = (Leg)routeSegment1.get(0);
						//stagedRoute.add(lastLeg1);
						//double walkingtime =  lastLeg1.getTravelTime();
						
						double maxChargingTime = 0;
						double actduration = 0;
						if (findAct.getEndTime().toString()=="OptionalTime[UNDEFINED]") {
							findAct.setEndTime(3600*30);
							actduration	= findAct.getEndTime().seconds() - lastArrivaltime;
							maxChargingTime = actduration;
						}else if(findAct.getEndTime().seconds() == 97200){						
							findAct.setEndTime(3600*30);
							actduration	= findAct.getEndTime().seconds() - lastArrivaltime;
							maxChargingTime = actduration;
						}else
							actduration	= findAct.getEndTime().seconds() - lastArrivaltime;
							maxChargingTime = actduration;
						double estimatedChargingPower = (maxChargingTime*maxPowerEstimate)/1.5;
						vehicle.setChargeUpTo(Math.min(estimatedChargingPower+afterConsumptionSoc,ev.getBatteryCapacity()));
						vehicle.getBattery().setEstimatedSoc(vehicle.getChargeUpTo());
						if(estimatedChargingPower+afterConsumptionSoc >= ev.getBatteryCapacity()) {
							estimatedChargingPower = ev.getBatteryCapacity() - afterConsumptionSoc;
						}
						double estimatedChargingTime = (estimatedChargingPower)*1.5 / maxPowerEstimate;
						workChargeAct.setMaximumDuration(estimatedChargingTime);
						//lastArrivaltime += chargeAct.getMaximumDuration().seconds();
						//lastArrivaltime += lastDeparture;
						//chargeAct.setMaximumDuration(chargeAct.getMaximumDuration().seconds());
						stagedRoute.add(workChargeAct);
						stagedRoute.add(lastLeg1);
						//vehicle.setChargeUpTo(ev.getBatteryCapacity());
						//vehicle.setChargeUpTo(ev.getBatteryCapacity());
						return stagedRoute;
						
					}else {
						return basicRoute;
					}
					
					
//				if((vehicle.getWorkParking() && findAct.getType()=="work") || vehicle.getPrivateParking() && findAct.getType()=="home") {
//					return basicRoute;
				}else if (isCoordInsidePolygon(findAct.getCoord(),getVertices()) == false) {
					return basicRoute;
				}
				
				
				else {
					List<PlanElement> stagedRoute = new ArrayList<>();
					Facility lastFrom = fromFacility;
					double lastArrivaltime = departureTime;
					Link toFacilityLink = network.getLinks().get(toFacility.getLinkId());
					StraightLineKnnFinder<Link, ChargerSpecification> straightLineKnnFinder = new StraightLineKnnFinder<>(
							2, l -> l, s -> network.getLinks().get(s.getLinkId()));
//					List<ChargerSpecification> nearestChargers = straightLineKnnFinder.findNearest(toFacilityLink,
//							chargingInfrastructureSpecification.getChargerSpecifications()
//									.values()
//									.stream()
//									.filter(charger -> ev.getChargerTypes().contains(charger.getChargerType())));
					List<ChargerSpecification> nearestChargers = straightLineKnnFinder.findNearest(toFacilityLink,
							chargingInfrastructureSpecification.getChargerSpecifications()
									.values()
									.stream()
									.filter(charger -> charger.getId().toString().length() < 10));
					// Select closest charger = no stohasticity
					ChargerSpecification selectedCharger = nearestChargers.get(0);
					//ChargerSpecification selectedCharger = nearestChargers.get(random.nextInt(1));
					Link selectedChargerLink = network.getLinks().get(selectedCharger.getLinkId());
					Facility nexttoFacility = new LinkWrapperFacility(selectedChargerLink);
					//if (nexttoFacility.getLinkId().equals(lastFrom.getLinkId())) {
					//	continue;
					//}
					List<? extends PlanElement> routeSegment = delegate.calcRoute(lastFrom, nexttoFacility,
							lastArrivaltime, person);
					Leg lastLeg = (Leg)routeSegment.get(0);
					lastArrivaltime = lastLeg.getDepartureTime() + lastLeg.getTravelTime();
					stagedRoute.add(lastLeg);
					Activity chargeAct = PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(selectedChargerLink.getCoord(),
							selectedChargerLink.getId(), stageActivityModePrefix);
					double maxPowerEstimate = Math.min(selectedCharger.getPlugPower(), ev.getBatteryCapacity()*0.2 / 3.6);
					double afterConsumptionSoc = battery.getSoc()-estimatedOverallConsumption;
					
					lastFrom = nexttoFacility;
					List<? extends PlanElement> routeSegment1 = walkRouter.calcRoute(lastFrom, toFacility, lastArrivaltime, person);
					Leg lastLeg1 = (Leg)routeSegment1.get(0);
					//stagedRoute.add(lastLeg1);
					double walkingtime =  lastLeg1.getTravelTime();
					//stagedRoute.addAll(walkRouter.calcRoute(lastFrom, toFacility, lastArrivaltime, person));
					double maxChargingTime = 0;
					double actduration = 0;
					if (findAct.getEndTime().toString()=="OptionalTime[UNDEFINED]") {
						findAct.setEndTime(3600*24);
						actduration	= findAct.getEndTime().seconds() - lastArrivaltime;
						maxChargingTime = Math.min(actduration+walkingtime,evConfigGroup.getMaximumChargeTime());
					}else if(findAct.getEndTime().seconds() == 97200){						
						findAct.setEndTime(3600*24);
						actduration	= findAct.getEndTime().seconds() - lastArrivaltime;
						maxChargingTime = Math.min(actduration+walkingtime,evConfigGroup.getMaximumChargeTime());
					}else
						actduration	= findAct.getEndTime().seconds() - lastArrivaltime;
						maxChargingTime = Math.min(actduration+walkingtime,evConfigGroup.getMaximumChargeTime());
					double estimatedChargingPower = (maxChargingTime*maxPowerEstimate)/1.5;
					vehicle.setChargeUpTo(Math.min(estimatedChargingPower+afterConsumptionSoc,ev.getBatteryCapacity()));
					vehicle.getBattery().setEstimatedSoc(vehicle.getChargeUpTo());
					if(estimatedChargingPower+afterConsumptionSoc >= ev.getBatteryCapacity()) {
						estimatedChargingPower = ev.getBatteryCapacity() - afterConsumptionSoc;
					}
					double estimatedChargingTime = (estimatedChargingPower)*1.5 / maxPowerEstimate;
					if(estimatedChargingTime < evConfigGroup.getMinimumChargeTime()) {
						return basicRoute;
					}
					chargeAct.setMaximumDuration(estimatedChargingTime);
					stagedRoute.add(chargeAct);
					stagedRoute.add(lastLeg1);
					return stagedRoute;
				}
			}

		}
	}

	private Map<Link, Double> estimateConsumption(ElectricVehicleSpecification ev, Leg basicLeg) {
		Map<Link, Double> consumptions = new LinkedHashMap<>();
		NetworkRoute route = (NetworkRoute)basicLeg.getRoute();
		List<Link> links = NetworkUtils.getLinks(network, route.getLinkIds());
		ElectricVehicle pseudoVehicle = MyElectricVehicleImpl.create(ev, driveConsumptionFactory, auxConsumptionFactory,
				v -> charger -> {
					throw new UnsupportedOperationException();
				});
		DriveEnergyConsumption driveEnergyConsumption = pseudoVehicle.getDriveEnergyConsumption();
		AuxEnergyConsumption auxEnergyConsumption = pseudoVehicle.getAuxEnergyConsumption();
		double lastSoc = pseudoVehicle.getBattery().getSoc();
		double linkEnterTime = basicLeg.getDepartureTime();
		for (Link l : links) {
			double travelT = travelTime.getLinkTravelTime(l, basicLeg.getDepartureTime(), null, null);

			double consumption = driveEnergyConsumption.calcEnergyConsumption(l, travelT, linkEnterTime)
					+ auxEnergyConsumption.calcEnergyConsumption(basicLeg.getDepartureTime(), travelT, l.getId());
			if(pseudoVehicle.getVehicleSize() == "small") {
				pseudoVehicle.getBattery().changeSoc(-consumption*CONSTANT.SmallCoef);
			}
			else if(pseudoVehicle.getVehicleSize() == "large") {
				pseudoVehicle.getBattery().changeSoc(-consumption*CONSTANT.LargeCoef);
			}
			else {
				pseudoVehicle.getBattery().changeSoc(-consumption);
			}
			double currentSoc = pseudoVehicle.getBattery().getSoc();
			// to accomodate for ERS, where energy charge is directly implemented in the consumption model
			double consumptionDiff = (lastSoc - currentSoc);
			lastSoc = currentSoc;
			consumptions.put(l, consumptionDiff);
			linkEnterTime += travelT;
		}
		return consumptions;
	}
	
	private static boolean isCoordInsidePolygon(Coord c, Coord[] v) {
        int j = v.length - 1;
        boolean oddNodes = false;
        for (int i = 0; i < v.length; i++) {
                if ((v[i].getY() < c.getY() && v[j].getY() >= c.getY()) || v[j].getY() < c.getY()
                               && v[i].getY() >= c.getY()) {
                        if (v[i].getX() + (c.getY() - v[i].getY()) / 
                                       (v[j].getY() - v[i].getY()) * (v[j].getX() - v[i].getX()) < c.getX()) {
                               oddNodes = !oddNodes;
                        }
                }
                j = i;
        }
        return oddNodes;
	}
	
	private static Coord[] getVertices() {
        LinkedList<Coord> coords = new LinkedList<Coord>();
        coords.addLast(new Coord(722086.08, 6174341.30)); // 
        coords.addLast(new Coord(718972.35, 6174272.17)); // 
        coords.addLast(new Coord(718919.14, 6176321.60)); // 
        coords.addLast(new Coord(719757.69, 6177852.24)); // 
        coords.addLast(new Coord(721098.86, 6178852.42)); // 
        coords.addLast(new Coord(722456.12, 6178605.02)); // 
        coords.addLast(new Coord(724273.49, 6177054.33)); // 
        coords.addLast(new Coord(724374.48, 6175777.20)); // 
        coords.addLast(new Coord(723648.26, 6174580.74)); // 

        Coord[] output = new Coord[coords.size()];
        for (int i = 0; i < output.length; i++) {
                            output[i] = coords.pollFirst();
        }
        return output;
} 
	

	@Override
	public String toString() {
		return "[NetworkRoutingModule: mode=" + this.mode + "]";
	}

}
