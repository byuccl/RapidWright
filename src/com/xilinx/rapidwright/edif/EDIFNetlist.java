/*
 * 
 * Copyright (c) 2017 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *
 * This file is part of RapidWright. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
/**
 * 
 */
package com.xilinx.rapidwright.edif;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Top level object for a (logical) EDIF netlist. 
 * 
 * Created on: May 11, 2017
 */
public class EDIFNetlist extends EDIFName {

	private Map<String, EDIFLibrary> libraries;
	
	private EDIFDesign design;
	
	private EDIFCellInst topCellInstance = null;
	
	private List<String> comments;
	
	private Map<String,EDIFPropertyValue> metax;
	
	private Map<String,String> parentNetMap;
	
	private Map<String, ArrayList<EDIFHierPortInst>> physicalNetPinMap;
	
	protected int nameSpaceUniqueCount = 0;

	private transient Device device;

	private Set<String> primsToRemoveOnCollapse = new HashSet<String>();
	
	private String origDirectory;
	
	private String[] encryptedCells; 
	
	private boolean DEBUG = false;

	public static final Map<String,String> macroExpandExceptionMap;
	public static final Map<String,String> macroCollapseExceptionMap;
	
	static {
	    macroExpandExceptionMap = new HashMap<>();
	    // Prim -> Macro (when name is not same)
	    macroExpandExceptionMap.put("OBUFTDS", "OBUFTDS_DUAL_BUF");
	    
	    macroCollapseExceptionMap = new HashMap<>();
	    for(Entry<String,String> e : macroExpandExceptionMap.entrySet()) {
	    	macroCollapseExceptionMap.put(e.getValue(), e.getKey());
	    }
	}
	
	public EDIFNetlist(String name){
		super(name);
		init();
	}
	
	protected EDIFNetlist(){
		init();
	}
	
	private void init(){
		libraries = getNewMap();
		comments = new ArrayList<>();
		metax = getNewMap();
	}
	
	/**
	 * Adds date and username build comments such as:
	 *  (comment "Built on 'Mon May  1 15:17:36 PDT 2017'")
  	 *  (comment "Built by 'clavin'")
	 */
	public void generateBuildComments(){
		addComment("Built on '"+FileTools.getTimeString()+"'");
		addComment("Built by '"+System.getenv().get("USER")+"'");
	}
	
	/**
	 * Adds the library to this netlist.  Checks for naming collisions
	 * and throws a RuntimeException if it occurs.
	 * @param library The library to add.
	 * @return The library that was added.
	 */
	public EDIFLibrary addLibrary(EDIFLibrary library){
		library.setNetlist(this);
		EDIFLibrary collision = libraries.put(library.getName(), library); 
		if(collision != null){
			throw new RuntimeException("ERROR: EDIFNetlist already has "
					+ "library named " + library.getName() );
		}
		return library;
	}

	public EDIFLibrary getLibrary(String name){
		return libraries.get(name);
	}
	
	public EDIFLibrary getHDIPrimitivesLibrary(){
		EDIFLibrary primLib = libraries.get(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME); 
		if(primLib == null){
			primLib = addLibrary(new EDIFLibrary(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME));
		}
		return primLib;
	}
	
	/**
	 * Will create or get the specified unisim cell and ensure it is added to the HDI 
	 * primitives library. If the cell is already in the library, it will simply get it
	 * and return it.
	 * @param unisim The desired Unisim cell type.
	 * @return The current unisim cell in the HDI primitive library for this netlist.
	 */
	public EDIFCell getHDIPrimitive(Unisim unisim){
		EDIFLibrary lib = getHDIPrimitivesLibrary();
		EDIFCell cell = lib.getCell(unisim.name());
		if(cell == null){
			cell = Design.getUnisimCell(unisim);
		}
		return lib.addCell(cell);
	}
	
	public EDIFLibrary getWorkLibrary(){
		EDIFLibrary primLib = libraries.get(EDIFTools.EDIF_LIBRARY_WORK_NAME); 
		if(primLib == null){
			primLib = addLibrary(new EDIFLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME));
		}
		return primLib;
	}
	
	public EDIFLibrary removeLibrary(String name){
		return libraries.remove(name);
	}
	
	public void renameNetlistAndTopCell(String newName){
		this.setName(newName);
		this.updateEDIFRename();
		design.setName(newName);
		design.updateEDIFRename();
		design.getTopCell().setName(newName);
		design.getTopCell().updateEDIFRename();
		if(topCellInstance != null){
			topCellInstance.setName(newName);
			topCellInstance.updateEDIFRename();
		}
	}
	
	/**
	 * Helper method for {@link #removeUnusedCellsFromAllLibraries()}
	 * @param cellsToRemove The map keeping track of unused cells
	 * @param cell Cell to delete from removal list
	 */
	private static void _keepCell(HashMap<String,HashMap<String,EDIFCell>> cellsToRemove, 
			EDIFCell cell) {
		EDIFLibrary lib = cell.getLibrary();
		if(lib.isHDIPrimitivesLibrary()) return;
		String libName = lib.getName();
		HashMap<String,EDIFCell> libCells = cellsToRemove.get(libName);
		if(libCells == null) {
			throw new RuntimeException("ERROR: Cell " + cell + " references unknown library " 
					+ libName);
		}
		libCells.remove(cell.getLegalEDIFName());
	}
	
	/**
	 * Removals all unused cells from a netlist from any work library (all except hdi_primitives) 
	 */
	public void removeUnusedCellsFromAllWorkLibraries() {
		HashMap<String,HashMap<String,EDIFCell>> cellsToRemove = new HashMap<>();
		for(EDIFLibrary lib : getLibraries()) {
			if(lib.isHDIPrimitivesLibrary()) continue;
			cellsToRemove.put(lib.getName(), new HashMap<>(lib.getCellMap()));
		}
		
		_keepCell(cellsToRemove, getTopCell());
		for(EDIFHierCellInst i : getAllDescendants("", null, false)){
			_keepCell(cellsToRemove, i.getCellType());
		}
		
		for(Entry<String, HashMap<String,EDIFCell>> e : cellsToRemove.entrySet()) {
			String libName = e.getKey();
			EDIFLibrary lib = getLibrary(libName);
			for(EDIFCell cell : e.getValue().values()) {
				lib.removeCell(cell);
			}
		}
	}
	
	public void removeUnusedCellsFromWorkLibrary(){
		HashMap<String,EDIFCell> cellsToRemove = new HashMap<>(getWorkLibrary().getCellMap());
		
		cellsToRemove.remove(getTopCell().getLegalEDIFName());
		for(EDIFHierCellInst i : getAllDescendants("", null, false)){
			if(i.getCellType().getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_WORK_NAME)){
				cellsToRemove.remove(i.getCellType().getLegalEDIFName());
			}
		}
		
		for(String name : cellsToRemove.keySet()){
			getWorkLibrary().removeCell(name);
		}
	}
	
	/**
	 * Iterates through libraries to find first cell with matching name and 
	 * returns it.
	 * @param legalEdifName The legal EDIF name of the cell to find.
	 * @return The first occurring cell with the provided name. 
	 */
	public EDIFCell getCell(String legalEdifName){
		for(EDIFLibrary lib : getLibraries()){
			EDIFCell c = lib.getCell(legalEdifName);
			if(c != null) return c;
		}
		return null;
	}
	
	/**
	 * @return the design
	 */
	public EDIFDesign getDesign() {
		return design;
	}

	/**
	 * @param design the design to set
	 */
	public void setDesign(EDIFDesign design) {
		this.design = design;
	}
	
	
	
	public Device getDevice() {
		if(device == null) {
			String partName = EDIFTools.getPartName(this);
			if(partName != null) {
				device = Device.getDevice(partName);
			}
			if(device == null) {
				System.err.println("WARNING: PART property on EDIF Design object not set correctly,"
						+ " currently set to '"+partName+"', couldn't load device.");
			}
		}
		return device;
	}

	public void setDevice(Device device) {
		this.device = device;
	}

	public EDIFCell getTopCell(){
		return design.getTopCell();
	}
	
	public EDIFCellInst getTopCellInst(){
		if(topCellInstance == null){
			topCellInstance = getTopCell().createCellInst("top", null);
		}
		return topCellInstance;
	}
	
	public boolean addComment(String comment){
		return comments.add(comment);
	}
	
	public EDIFPropertyValue addMetax(String key, EDIFPropertyValue value){
		return metax.put(key, value);
	}

	/**
	 * @return the comments
	 */
	public List<String> getComments() {
		return comments;
	}

	/**
	 * Migrates all cells in the provided library
	 * into the standard work library.  
	 * @param library The library with cells to be migrated to work.
	 */
	public void migrateToWorkLibrary(String library) {
		EDIFLibrary work = getWorkLibrary();
		EDIFLibrary oldWork = getLibrary(library);
		List<EDIFCell> toRemove = new ArrayList<>(oldWork.getCells());
		for (EDIFCell c : toRemove) {
			work.addCell(c);
			oldWork.removeCell(c);
		}
		removeLibrary(library);
	}

	/**
	 * Migrates all libraries except HDI primitives and work to 
	 * the work library.
	 */
	public void consolidateAllToWorkLibrary() {
		List<EDIFLibrary> librariesToMigrate = new ArrayList<>();
		for (EDIFLibrary l : getLibraries()) {
			if (!l.isHDIPrimitivesLibrary() && !l.isWorkLibrary()) {
				librariesToMigrate.add(l);
			}
		}
		for (EDIFLibrary l : librariesToMigrate) {
			migrateToWorkLibrary(l.getName());
		}
	}

	private EDIFCell migrateCellAndSubCellsWorker(EDIFCell cell) {
		EDIFLibrary destLib = getLibrary(cell.getLibrary().getName());
		if(destLib == null){
			if(cell.getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME)){
				destLib = getHDIPrimitivesLibrary();
			}else{
				destLib = getWorkLibrary();
			}
		}

		EDIFCell existingCell = destLib.getCell(cell.getLegalEDIFName());
		if(existingCell == null){
			destLib.addCell(cell);
			for(EDIFCellInst inst : cell.getCellInsts()){
				inst.updateCellType(migrateCellAndSubCellsWorker(inst.getCellType()));
				//The view might have changed
				inst.getViewref().setName(inst.getCellType().getView());
			}
			return cell;
		} else {
			return existingCell;
		}
	}
	
	public void migrateCellAndSubCells(EDIFCell cell) {
		migrateCellAndSubCellsWorker(cell);
	}


	public void migrateCellAndSubCells(EDIFCell cell, boolean uniqueifyCollisions){
		if (!uniqueifyCollisions){
			migrateCellAndSubCells(cell);
			return;
		}

		Queue<EDIFCell> cells = new LinkedList<>(); // which contains cells that have been added to libraries but whose subcells haven't.
		//Step 1: add the top cell to the library.
		//If the top cell belongs to HDIPrimitivesLibrary && the top cell exists in HDIPrimitivesLibrary, return and do nothing.
		//Otherwise, the code would add the top cell to the library; if repeat happens, using "parameterized" suffix to distinguish
		EDIFLibrary destLibTop = getLibrary(cell.getLibrary().getName());
		if(destLibTop == null){
			if(cell.getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME)){
				destLibTop = getHDIPrimitivesLibrary();
			}else{
				destLibTop = getWorkLibrary();
			}
		}
		if (destLibTop.containsCell(cell) && destLibTop.getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME))
			return;
		int i=0;
		String currentCellName = cell.getName();
		while (destLibTop.containsCell(cell)) {
			cell.setName(currentCellName + "_parameterized" + i);
			cell.setView(currentCellName + "_parameterized" + i);
			cell.updateEDIFRename();
			i++;
		}
		destLibTop.addCell(cell);
		cells.add(cell);

		//Step 2: add the subcells, subsubcells... to the library.
		//Do it like before, but updating the celltype of each cellInst should be noticed.
		while(!cells.isEmpty()){
			EDIFCell pollFromCells = cells.poll();
			for(EDIFCellInst inst : pollFromCells.getCellInsts()) {
				EDIFCell instCellType = inst.getCellType();
				EDIFLibrary destLibSub = getLibrary(instCellType.getLibrary().getName());
				if (destLibSub == null) {
					if (instCellType.getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME)) {
						destLibSub = getHDIPrimitivesLibrary();
					} else {
						destLibSub = getWorkLibrary();
					}
				}
				if (destLibSub.containsCell(instCellType) && destLibSub.getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME))
					continue;
				i=0;
				currentCellName = instCellType.getName();
				if(checkIfAlreadyInLib(instCellType, destLibSub)) {
					inst.setViewref(instCellType.getEDIFView());
					continue;
				}
				while (destLibSub.containsCell(instCellType) && !checkIfAlreadyInLib(instCellType, destLibSub)) {
					String newName = currentCellName + "_parameterized" + i;
					instCellType.setName(newName);
					instCellType.setView(newName);
					instCellType.updateEDIFRename();
					i++;
				}
				inst.setCellType(instCellType); // updating the celltype, which could be changed due to adding suffix
				destLibSub.addCell(instCellType);
				cells.add(instCellType);
			}
		}
	}
	
	private boolean checkIfAlreadyInLib(EDIFCell cell, EDIFLibrary lib) {
		EDIFCell existing = lib.getCell(cell.getLegalEDIFName());
		if(existing == cell && lib.getNetlist() == cell.getLibrary().getNetlist()) {
			return true;
		}
		return false;
	}
	
	/**
	 * Will change the netlist name and top cell and instance name.
	 * @param newName New name for the netlist
	 */
	public void changeTopName(String newName){
		this.setName(newName);
		this.design.setName(newName);
		EDIFCell top = this.design.getTopCell(); 
		EDIFLibrary lib = top.getLibrary();
		top.getLibrary().removeCell(top);
		top.setName(newName);
		lib.addCell(top);
	}
	
	/**
	 * @return the libraries
	 */
	public Map<String, EDIFLibrary> getLibrariesMap() {
		return libraries;
	}
	
	public Collection<EDIFLibrary> getLibraries(){
		return libraries.values();
	}
	
	/**
	 * Get Libraries in export order so that any cell instance appearing in a library will only 
	 * refer to cells in its own library or previous libraries in the list.  This is a pre-requisite
	 * for export to a file.
	 * @return List of all libraries in the netlist sorted for valid export, HDIPrimitives library
	 * is always first.
	 */
	public List<EDIFLibrary> getLibrariesInExportOrder() {
		Set<EDIFLibrary> toExport = new LinkedHashSet<EDIFLibrary>();
		// Assume HDI Primitives are always first as they should not refer to any previous libraries
		toExport.add(getHDIPrimitivesLibrary());
		
		Map<String, HashSet<EDIFLibrary>> deps = new HashMap<String, HashSet<EDIFLibrary>>();
		for(EDIFLibrary lib : getLibraries()) {
			if(lib.isHDIPrimitivesLibrary()) continue;
			HashSet<EDIFLibrary> externalRefs = 
					new HashSet<EDIFLibrary>(lib.getExternallyReferencedLibraries());
			externalRefs.remove(getHDIPrimitivesLibrary());
			
			if(externalRefs.isEmpty()) {
				toExport.add(lib);
			} else {
				deps.put(lib.getName(), externalRefs);
			}
		}
		
		Queue<Entry<String, HashSet<EDIFLibrary>>> q = new LinkedList<>(deps.entrySet());
		int lastSize = q.size();
		int size = lastSize;
		int watchdog = 10;
		while(!q.isEmpty()) {
			Entry<String,HashSet<EDIFLibrary>> curr = q.poll();
			size--;
			if(toExport.containsAll(curr.getValue())) {
				toExport.add(getLibrary(curr.getKey()));
				continue;
			} 
			q.add(curr);
			if(!q.isEmpty() && size == 0) {
				if(q.size() == lastSize) {
					watchdog--;
					if(watchdog == 0) {
						throw new RuntimeException("Circular dependency in EDIF Libraries between "
								+ "cells.  Please merge libraries or resolve dependency.");
					}
					lastSize = q.size();
					size = lastSize;
				}
			}
		}
		
		return new ArrayList<>(toExport);
	}
	
	public void exportEDIF(String fileName){
		BufferedWriter bw = null;
		
		//for(EDIFLibrary lib : getLibraries()){
		//	lib.ensureValidEDIFCellNames();
		//}
		
		try {
			bw = new BufferedWriter(new FileWriter(fileName));
			bw.write("(edif ");
			exportEDIFName(bw);
			bw.write("\n");
			bw.write("  (edifversion 2 0 0)\n");
			bw.write("  (edifLevel 0)\n");
			bw.write("  (keywordmap (keywordlevel 0))\n");
			bw.write("(status\n");
			bw.write(" (written\n");
			bw.write("  (timeStamp ");
			SimpleDateFormat formatter = new SimpleDateFormat("yyyy MM dd HH mm ss");
			bw.write(formatter.format(new java.util.Date()));
			bw.write(")\n");
			bw.write("  (program \""+Device.FRAMEWORK_NAME+"\" (version \"" + Device.RAPIDWRIGHT_VERSION + "\"))\n");
			for(String comment : getComments()){
				bw.write("  (comment \"");
				bw.write(comment);
				bw.write("\")\n");
			}
			for(Entry<String,EDIFPropertyValue> e : metax.entrySet()){
				bw.write("(metax ");
				bw.write(e.getKey());
				bw.write(" ");
				e.getValue().writeEDIFString(bw);
				bw.write(")\n");
			}
			bw.write(" )\n");
			bw.write(")\n");
			
			getHDIPrimitivesLibrary().exportEDIF(bw);
			for(EDIFLibrary lib : getLibrariesMap().values()){
				if(lib.getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME)) continue;
				lib.exportEDIF(bw);
			}
			bw.write("(comment \"Reference To The Cell Of Highest Level\")\n\n");
			bw.write("  (design ");
			EDIFDesign design = getDesign(); 
			design.exportEDIFName(bw);
			bw.write("\n    (cellref " + design.getTopCell().getLegalEDIFName() + " (libraryref ");
			bw.write(design.getTopCell().getLibrary().getLegalEDIFName() +"))\n");
			design.exportEDIFProperties(bw, "    ");
			bw.write("  )\n");
			bw.write(")\n");
			bw.flush();
			bw.close();
		} catch (IOException e) {
			MessageGenerator.briefError("ERROR: Failed to export EDIF file " + fileName);
			e.printStackTrace();
		}
	}
	
	/**
	 * Based on a hierarchical string, this method will get the instance corresponding
	 * to the name provided.
	 * @param name Hierarchical name of the instance, for example: 'clk_wiz/inst/bufg0'
	 * @return The instance corresponding to the provided name.  If the name string is empty,
	 * it returns the top cell instance.
	 */
	public EDIFCellInst getCellInstFromHierName(String name){
		EDIFCellInst currInst = getTopCellInst();
		if(name.isEmpty()) return currInst;
		String[] parts = name.split(EDIFTools.EDIF_HIER_SEP);
		
		// Sadly, cells can be named 'fred/' instead of 'fred', this code handles this situation
		if(name.charAt(name.length()-1) == '/') {
			parts[parts.length-1] = parts[parts.length-1] + EDIFTools.EDIF_HIER_SEP;  
		}
		
		for(int i=0; i < parts.length; i++){
			EDIFCellInst checkInst = currInst.getCellType().getCellInst(parts[i]);
			// Someone named their instance with hierarchy separators, joy!
			if(checkInst == null){
				StringBuilder sb = new StringBuilder(parts[i]);
				i++;
				while(checkInst == null && i < parts.length){
					sb.append(EDIFTools.EDIF_HIER_SEP);
					sb.append(parts[i]);
					checkInst = currInst.getCellType().getCellInst(sb.toString());
					if(checkInst == null) i++;
				}
			}
			currInst = checkInst;
		}
		return currInst;
	}
	
	/**
	 * Based on a hierarchical string name, this method gets and returns the net inside
	 * the instance.  
	 * @param netName The hierarchical name of the net to get, for example: 'inst0/inst1/inst2/net0'
	 * @return The hierarchical net, or null if none could be found.
	 */
	public EDIFNet getNetFromHierName(String netName){
		EDIFHierNet net = getHierNetFromName(netName);
		return net == null ? null : net.getNet();
	}
	
	/**
	 * Gets the hierarchical port instance object from the full name.
	 * @param hierPortInstName Full hierarchical name of the port instance. 
	 * @return The port instance of interest or null if none could be found.
	 */
	public EDIFHierPortInst getHierPortInstFromName(String hierPortInstName){
		String instName = "";
		String localPortName = hierPortInstName;
		int lastSep = hierPortInstName.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
		if(lastSep != -1){
			instName = hierPortInstName.substring(0,lastSep);
			localPortName = hierPortInstName.substring(lastSep+1);
		}
		
		EDIFCellInst inst = getCellInstFromHierName(instName);
		if(inst == null) return null;
		EDIFPortInst port = inst.getPortInst(localPortName);
		if(port == null) return null;
		
		String parentInstName = getHierParentName(instName);
		EDIFHierPortInst hierPortInst = new EDIFHierPortInst(parentInstName,port);
		
		return hierPortInst;
	}
	
	/**
	 * Looks at the hierarchical name and returns the parent or instance above.  For example:
	 * "block0/operator0" -> "block0"; "block0" -> ""; "" -> ""
	 * @param hierReferenceName Hierarchical reference name
	 * @return 
	 */
	public static String getHierParentName(String hierReferenceName){
		if(hierReferenceName == null) return null;
		if(hierReferenceName.length() == 0) return hierReferenceName;
		int lastSep = hierReferenceName.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
		if(lastSep != -1){
			return hierReferenceName.substring(0,lastSep);
		}		
		return "";
	}
	
	/**
	 * Gets the next level hierarchical child instance name from an ancestor. Assumes descendent is
	 * instantiated within ancestor at some level.  
	 * 
	 * For example:
	 * getNextHierChildName("a/b/c", "a/b/c/d/e") returns "a/b/c/d"
	 * getNextHierChildName("a/b/c", "a/b/c/d") returns "a/b/c/d"
	 * getNextHierChildName("a/b/c", "a/b/d") returns null
	 * getNextHierChildName("a/b/c", "a/b/c") returns null
	 * 
	 * @param ancestor The parent or more shallow instance in a netlist 
	 * @param descendent The child or deeper instance in a netlist
	 * @return The name of the next hierarchical child instance in the ancestor/descendent chain.  
	 * Returns null if none could be found.  
	 */
	public static String getNextHierChildName(String ancestor, String descendent) {
		if(ancestor == null || descendent == null) return null;
		if(!descendent.startsWith(ancestor)) return null;
		if(ancestor.equals(descendent)) return null;
		int nextHierSeparator = descendent.indexOf(EDIFTools.EDIF_HIER_SEP, ancestor.length()+1);
		if(nextHierSeparator == -1) return descendent;
		return descendent.substring(0,nextHierSeparator);
	}
	
	/**
	 * Creates a new hierarchical cell instance reference from the provided hierarchical cell 
	 * instance name
	 * @param instName Full hierarchical cell instance name
	 * @return Hierarchical cell instance reference or null if named instance could not be found
	 */
	public EDIFHierCellInst getHierCellInstFromName(String instName) {
		EDIFCellInst inst = getCellInstFromHierName(instName);
		String parentName = null;
		if(instName != null) {
			if(inst == null) {
				System.out.println("instName=" + instName + " led to null inst");
			}
			int lastOccurrance = instName.lastIndexOf(inst.getName());
			parentName = lastOccurrance == 0 ? "" : instName.substring(0, lastOccurrance-1);			
		}
		return new EDIFHierCellInst(parentName, inst);
	}
	
	/**
	 * Gets the hierarchical net from the netname provided. Returns the wrapped EDIFNet, with the hierarchical
	 * String in {@link EDIFHierNet}.
	 * @param netName Full hierarchical name of the net to retrieve. 
	 * @return The absolute net with hierarchical name, or null if none could be found.
	 */
	public EDIFHierNet getHierNetFromName(String netName){
		String instName = "";
		String localNetName = netName;
		int lastSep = netName.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
		if(lastSep != -1){
			instName = netName.substring(0,lastSep);
			localNetName = netName.substring(lastSep+1);
		}
		EDIFCellInst i = getCellInstFromHierName(instName);
		EDIFNet net = i == null ? null : i.getCellType().getNet(localNetName);
		if(i == null || net == null){
			// Maybe instance or net name contains '/', try a few different alternatives
			while(net == null && instName.contains(EDIFTools.EDIF_HIER_SEP)){
				lastSep = instName.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
				instName = netName.substring(0,lastSep);
				localNetName = netName.substring(lastSep+1);
				i = getCellInstFromHierName(instName);
				net = i == null ? null : i.getCellType().getNet(localNetName);
			}
			if(net == null){
				return null;
			}
			
		}
		EDIFHierNet an = new EDIFHierNet(instName, net);
		return an;
	}

	public Net getPhysicalNetFromPin(String parentHierInstName, EDIFPortInst p, Design d){
		String hierarchicalNetName = null;
		if(parentHierInstName.isEmpty()){
			hierarchicalNetName = p.getNet().getName();
		}else{
			hierarchicalNetName = parentHierInstName + EDIFTools.EDIF_HIER_SEP + p.getNet().getName();
		}
		if(hierarchicalNetName.equals(EDIFTools.LOGICAL_GND_NET_NAME)) return d.getGndNet();
		if(hierarchicalNetName.equals(EDIFTools.LOGICAL_VCC_NET_NAME)) return d.getVccNet();
		
		Map<String,String> parentNetMap = getParentNetMap();
		String parentNetName = parentNetMap.get(hierarchicalNetName);
		Net n = d.getNet(parentNetName);
		if(n == null){
			if(parentNetName == null){
				// Maybe it is GND/VCC
				List<EDIFPortInst> src = p.getNet().getSourcePortInsts(false);
				if(src.size() > 0 && src.get(0).getCellInst() != null){
					String cellType = src.get(0).getCellInst().getCellType().getName();
					if(cellType.equals("GND")) return d.getGndNet();
					if(cellType.equals("VCC")) return d.getVccNet();
				}
			}
			if(parentNetName == null) {
				System.err.println("WARNING: Could not find parent of net \"" + hierarchicalNetName +
						"\", please check that the netlist is fully connected through all levels of "
						+ "hierarchy for this net.");
			}
			EDIFNet logicalNet = getNetFromHierName(parentNetName);
			List<EDIFPortInst> eprList = logicalNet.getSourcePortInsts(false);
			if(eprList.size() > 1) throw new RuntimeException("ERROR: Bad assumption on net, has two sources.");
			if(eprList.size() == 1){
				String cellTypeName = eprList.get(0).getCellInst().getCellType().getName();
				if(cellTypeName.equals("GND")){
					return d.getGndNet();
				}else if(cellTypeName.equals("VCC")){
					return d.getVccNet();
				}				
			}
			// If size is 0, assume top level port in an OOC design

			n = d.createNet(parentNetName);
			n.setLogicalNet(logicalNet);
		}
		return n;
	}
	
	/**
	 * Searches all EDIFCellInst objects to find those with matching names
	 * against the wildcard pattern.  
	 * @param wildcardPattern Search pattern that includes alphanumeric and wildcards (*).
	 * @return The list of all matching EDIFHierCellInst 
	 */
	public List<EDIFHierCellInst> findCellInsts(String wildcardPattern){
		return getAllDescendants("", wildcardPattern, false);
	}
	
	/**
	 * Searches all lower levels of hierarchy to find all leaf descendants.  It returns a
	 * list of all leaf cells that fall under the hierarchy of the provided instance name.
	 * @param instanceName Name of the instance to start searching from.
	 * @return A list of all leaf cell instances or null if the instanceName was not found.
	 */
	public List<EDIFHierCellInst> getAllLeafDescendants(String instanceName){
		List<EDIFHierCellInst> leafCells = new ArrayList<>();
		
		EDIFCellInst currTop = getCellInstFromHierName(instanceName);
		
		Queue<EDIFHierCellInst> toProcess = new LinkedList<EDIFHierCellInst>();
		EDIFHierCellInst eci = new EDIFHierCellInst(EDIFTools.getHierarchicalRootFromPinName(instanceName), currTop);
		toProcess.add(eci);
		
		while(!toProcess.isEmpty()){
			EDIFHierCellInst curr = toProcess.poll();
			if(curr.getCellType().isPrimitive()){
				leafCells.add(curr);
			}else{
				for(EDIFCellInst i : curr.getInst().getCellType().getCellInsts()){
					toProcess.add(new EDIFHierCellInst(curr.getFullHierarchicalInstName(), i));
				}
			}
		}
		return leafCells;
	}
	
	private String convertWildcardToRegex(String wildcardPattern){
		if(wildcardPattern == null) return null;
		StringBuilder sb = new StringBuilder();
		for(int i=0; i < wildcardPattern.length(); i++){
			char c = wildcardPattern.charAt(i);
			switch (c) {
				case '*':
					sb.append(".*");
					break;
				case '?': case '\\': case '{': case '}': case '|':
				case '^': case '$':  case '(': case ')': case '[': case ']':
					sb.append("\\");
					sb.append(c);
					break;
				default:
					sb.append(c);
			}
		}
		sb.append("$");
		return sb.toString();
	}

	public List<EDIFHierCellInst> getAllLeafDescendants(String instanceName, String wildcardPattern){
		return getAllDescendants(instanceName, wildcardPattern, true);
	}
		
	
	/**
	 * Searches all lower levels of hierarchy to find descendants.  It returns the
	 * set of all cells that fall under the hierarchy of the provided instance name.
	 * @param instanceName Name of the instance to start searching from.
	 * @param wildcardPattern if non-null, filters results by matching wildcard pattern
	 * @param leavesOnly Flag indicating if only leaf cells should be included
	 * @return A set of all leaf cell instances or null if the instanceName was not found.
	 */
	public List<EDIFHierCellInst> getAllDescendants(String instanceName, String wildcardPattern, boolean leavesOnly){
		List<EDIFHierCellInst> children = new ArrayList<>();
		
		EDIFCellInst eci = getCellInstFromHierName(instanceName);
		if(eci == null) return null;
		Queue<EDIFHierCellInst> q = new LinkedList<>();
		q.add(new EDIFHierCellInst(instanceName, eci));
		String pattern = convertWildcardToRegex(wildcardPattern);
		Pattern pat = wildcardPattern != null ? Pattern.compile(pattern) : null;
		
		while(!q.isEmpty()){
			EDIFHierCellInst i = q.poll();
			for(EDIFCellInst child : i.getInst().getCellType().getCellInsts()){
				String fullName = "";
				if(!i.isTopLevelInst()){
					fullName = i.getFullHierarchicalInstName();
				}
				EDIFHierCellInst newCell = new EDIFHierCellInst(fullName, child);
				if(newCell.getInst().getCellType().isPrimitive()){
					if(pat != null && !pat.matcher(newCell.getFullHierarchicalInstName()).matches()){
						continue;
					}
					children.add(newCell);
				} else{
					q.add(newCell);
					if(!leavesOnly) {
						if(pat != null && !pat.matcher(newCell.getFullHierarchicalInstName()).matches()){
							continue;
						}
						children.add(newCell);
					}
				}
			}
		}
		
		return children;
	}
	
	private static boolean isDeviceNullPrinted = false;
	private boolean isTransformPrim(EDIFHierPortInst p){
		EDIFCellInst cellInst = p.getPortInst().getCellInst();
		if(!cellInst.getCellType().isPrimitive()) return false;
		Unisim u = Unisim.valueOf(p.getPortInst().getCellInst().getCellType().getName());
		if(device == null && !isDeviceNullPrinted){
			System.err.println("WARNING: EDIFNetlist.device==null when calling isTransformPrim(), results may be incorrect");
			isDeviceNullPrinted = true;
		}
		return u.hasTransform(device == null ? Series.UltraScale : device.getSeries());
	}
	
	/**
	 * TODO - Revisit this code, simplify, remove duplication
	 * Get's all equivalent nets in the netlist from the provided net name. 
	 * The returned list also includes the provided netName.
	 * @param netName Full hierarchical netname to use as a starting point in the search.
	 * @return A list of all electrically connected nets in the netlist that are equivalent.  
	 * The list is composed of all full hierarchical net names or an empty list if netName is invalid.
	 */
	public List<String> getNetAliases(String netName){	
		if(physicalNetPinMap == null){
			physicalNetPinMap = new HashMap<String,ArrayList<EDIFHierPortInst>>();
		}
		String parentNetName = null;
		ArrayList<EDIFHierPortInst> leafCellPins = new ArrayList<>();
		List<String> aliases = new ArrayList<>();
		aliases.add(netName);
		EDIFHierNet an = getHierNetFromName(netName);
		if(an == null) return Collections.emptyList();
		Queue<EDIFHierPortInst> queue = new LinkedList<>();
		EDIFPortInst source = null;
		for(EDIFPortInst p : an.getNet().getPortInsts()){
			EDIFHierPortInst absPortInst = new EDIFHierPortInst(an.getHierarchicalInstName(), p);
			// Checks if cell is primitive or black box
			boolean isCellPin = p.getCellInst() != null && p.getCellInst().getCellType().isLeafCellOrBlackBox();
			if(isCellPin){
				leafCellPins.add(absPortInst);
			}
			if((p.getCellInst() == null && p.isInput()) || (isCellPin && p.isOutput())){
				source = p;
				parentNetName = netName;
			}
			queue.add(absPortInst);
		}
		HashSet<String> visited = new HashSet<>();
		while(!queue.isEmpty()){
			EDIFHierPortInst p = queue.poll();
			visited.add(p.toString());
			EDIFNet otherNet = null;
			if(p.getPortInst().getCellInst() == null){
				// Moving up in hierarchy
				EDIFCellInst inst = getCellInstFromHierName(p.getHierarchicalInstName());
				EDIFPortInst epr = inst.getPortInst(p.getPortInst().getPortInstNameFromPort());
				if(epr == null){
					if(parentNetName == null && getTopCellInst().equals(inst) && p.getPortInst().isOutput()){
						source = p.getPortInst();
						parentNetName = p.getPortInst().getNet().getName();
					}
					continue;
				}
				otherNet = epr.getNet();
				int lastIndex = p.getHierarchicalInstName().lastIndexOf(EDIFTools.EDIF_HIER_SEP);
				String instName = lastIndex > 0 ? p.getHierarchicalInstName().substring(0, lastIndex) : "";
				EDIFCellInst checkInst = getCellInstFromHierName(instName);
				while(checkInst == null && lastIndex > 0){
					// Check for cells with hierarchy separator in their name
					lastIndex = p.getHierarchicalInstName().lastIndexOf(EDIFTools.EDIF_HIER_SEP, lastIndex-1);
					instName = p.getHierarchicalInstName().substring(0, lastIndex);
					checkInst = getCellInstFromHierName(instName);
				}
				StringBuilder sb = new StringBuilder(instName);
				if(!instName.isEmpty()) sb.append(EDIFTools.EDIF_HIER_SEP);
				sb.append(otherNet);
				aliases.add(sb.toString());
				for(EDIFPortInst opr : otherNet.getPortInsts()){
					if(epr.getPort() != opr.getPort()){ // Here we really want to compare object references!
						EDIFHierPortInst absPortInst = new EDIFHierPortInst(instName, opr);
						if(opr.getCellInst() != null && opr.getCellInst().getCellType().isLeafCellOrBlackBox()){
							leafCellPins.add(absPortInst);
							if(parentNetName == null && opr.isOutput()) {
								source = opr;
								parentNetName = netName;
							}
						}
						if(visited.contains(absPortInst.toString())) {
							//System.out.println(" DUPLICATE ENTRY: " + absPortInst);
							continue;
						}
						queue.add(absPortInst);
					}
				}
			}else if(p.isOutput() && isTransformPrim(p)){
				if(p.getPortInst().getPort().getWidth() > 1){
					aliases.add(p.getTransformedNetName());
				}else{
					aliases.add(p.toString());					
				}

			}else{
				// Moving down in hierarchy
				EDIFPort port = p.getPortInst().getPort();
				if(port != null && port.getParentCell().hasContents()){
					otherNet = port.getParentCell().getInternalNet(p.getPortInst());
					if(otherNet == null){
						// Looks unconnected
						continue;
					}
					StringBuilder sb = new StringBuilder(p.getHierarchicalInstName());
					if(!p.getHierarchicalInstName().isEmpty()) sb.append(EDIFTools.EDIF_HIER_SEP);
					sb.append(p.getPortInst().getCellInst().getName());
					String instName = sb.toString();
					sb.append(EDIFTools.EDIF_HIER_SEP);
					sb.append(otherNet.getName());
					aliases.add(sb.toString()); 
					
					for(EDIFPortInst ipr : otherNet.getPortInsts()){
						EDIFPort currPort = ipr.getPort();
						if(currPort.getName().equals(port.getName()) && 
								currPort.getParentCell().equals(port.getParentCell())) {
							continue;
						}
						EDIFHierPortInst absPortInst = new EDIFHierPortInst(instName, ipr);
						boolean isCellPin = ipr.getCellInst() != null && 
											ipr.getCellInst().getCellType().isLeafCellOrBlackBox();
						if(isCellPin){
							leafCellPins.add(absPortInst);
						}
						if((ipr.getCellInst() == null && ipr.isInput()) || (isCellPin && ipr.isOutput())){
							source = ipr;
							parentNetName = netName;
						}
						if(visited.contains(absPortInst.toString())) {
							//System.out.println("DUPLICATE ENTRY: " + absPortInst);
							continue;
						}
						queue.add(absPortInst);
					}
				}
			}
		}
		
		if(parentNetName != null){
			String cellType = source.getCellInst() == null ? "" : source.getCellInst().getCellType().getName();
			String staticNetName = cellType.equals("GND") ? Net.GND_NET : (cellType.equals("VCC") ? Net.VCC_NET : null); 
			if(staticNetName != null){
				ArrayList<EDIFHierPortInst> existing = physicalNetPinMap.get(staticNetName);
				if(existing == null) 
					physicalNetPinMap.put(staticNetName, leafCellPins);
				else 
					existing.addAll(leafCellPins);
			}else{
				physicalNetPinMap.put(parentNetName, leafCellPins);
			}
		} else if(an.getNet().getPortInsts().size() == 0){
			return aliases;
		} else{
			throw new RuntimeException("ERROR: Couldn't identify parent net, no output pins (or top level output port) found.");
		}
		
		return aliases;
	}

	/**
	 * Gets the canonical net for this net name.  This corresponds to the driving net
	 * in the netlist and/or the physical net name.
	 * @param netAlias An absolute net name alias (from logical netlist) 
	 * @return The physical/parent net name or null if none could be found.
	 */
	public String getParentNetName(String netAlias){
		return getParentNetMap().get(netAlias);
	}
	
	public Map<String,String> getParentNetMap(){
		if(parentNetMap == null){
			generateParentNetMap();
		}
		return parentNetMap;
	}
	
	/**
	 * Resets the internal parent net map of the netlist.  This is necessary any time modifications 
	 * are made to the netlist (add/remove/change cells/nets, removing/adding black boxes, etc). 
	 */
	public void resetParentNetMap(){
		parentNetMap = null;
		physicalNetPinMap = null;
	}
	
	private void generateParentNetMap(){
		long start = 0;
		if(DEBUG){
			start = System.currentTimeMillis();
		}
		if(parentNetMap == null){
			parentNetMap = new HashMap<>();
		}
		if(physicalNetPinMap == null){
			physicalNetPinMap = new HashMap<String,ArrayList<EDIFHierPortInst>>();
		}
		EDIFCell c = getTopCell();
		Queue<EDIFHierPortInst> queue = new LinkedList<>();
		// All parent nets are either top-level inputs or outputs of leaf cells
		// Here we gather all top-level inputs
		for(EDIFNet n : c.getNets()){
			for(EDIFPortInst p : n.getPortInsts()){
				if(p.isTopLevelPort() && p.isInput()){
					queue.add(new EDIFHierPortInst("", p));
				}
			}
		}
		// Here we search for all leaf cell insts 
		Queue<EDIFHierCellInst> instQueue = new LinkedList<>();
		instQueue.add(new EDIFHierCellInst("", getTopCellInst()));
		while(!instQueue.isEmpty()){
			EDIFHierCellInst currInst = instQueue.poll(); 
			for(EDIFCellInst eci : currInst.getInst().getCellType().getCellInsts()){
				// Checks if cell is primitive or black box
				if(eci.getCellType().getCellInsts().size() == 0 && eci.getCellType().getNets().size() == 0){
					for(EDIFPortInst portInst : eci.getPortInsts()){
						if(portInst.isOutput()){
							queue.add(new EDIFHierPortInst(currInst.getFullHierarchicalInstName(), portInst));
						}
					}
				}else{
					String hName = currInst.getFullHierarchicalInstName();
					instQueue.add(new EDIFHierCellInst(hName,eci));
				}
			}
		}
		
		for(EDIFHierPortInst pr : queue){
			String parentNetName = pr.getHierarchicalNetName();
			for(String alias : getNetAliases(parentNetName)){
				parentNetMap.put(alias, parentNetName);
			}
		}
		if(DEBUG){
			long stop = System.currentTimeMillis();
			System.out.println("generateParentNetMap() runtime: " + (stop-start)/1000.0f +" seconds ");
		}
	}
	
	/**
	 * Traverses the netlist and produces a list of all primitive leaf cell instances.
	 * @return A list of all primitive leaf cell instances.
	 */
	public List<EDIFCellInst> getAllLeafCellInstances(){
		List<EDIFCellInst> insts = new ArrayList<>();
		Queue<EDIFCellInst> q = new LinkedList<>();
		q.add(getTopCellInst());
		while(!q.isEmpty()){
			EDIFCellInst curr = q.poll();
			for(EDIFCellInst eci : curr.getCellType().getCellInsts()){
				if(eci.getCellType().isPrimitive())
					insts.add(eci);
				else
					q.add(eci);
			}
		}
		return insts;
	}
	
	/**
	 * @return the physicalNetPinMap
	 */
	public Map<String, ArrayList<EDIFHierPortInst>> getPhysicalNetPinMap() {
		if(physicalNetPinMap == null){
			generateParentNetMap();
		}
		return physicalNetPinMap;
	}
	
	public List<EDIFHierPortInst> getPhysicalPins(String parentNetName) {
		return getPhysicalNetPinMap().get(parentNetName);
	}

	/**
	 * Gets all the primitive pin sinks that are strict descendants of
	 * this provided net.
	 * @param net The net to trace to its sinks.
	 * @return The list of all sink pins on primitive cells that are descendants 
	 * of the provided net 
	 */
	public List<EDIFHierPortInst> getSinksFromNet(EDIFHierNet net){
		Queue<EDIFHierNet> q = new LinkedList<>();
		q.add(net);
		ArrayList<EDIFHierPortInst> sinks = new ArrayList<>();
		HashSet<String> visited = new HashSet<>();
		while(!q.isEmpty()){
			EDIFHierNet curr = q.poll();
			if(visited.contains(curr.getHierarchicalNetName())) continue;
			visited.add(curr.getHierarchicalNetName());
			for(EDIFPortInst portInst : curr.getNet().getPortInsts()){
				if(portInst.isOutput()) continue;
				if(portInst.isTopLevelPort()){
					// Going up in hierarchy
					EDIFCellInst cellInst = getCellInstFromHierName(curr.getHierarchicalInstName());
					if(cellInst == null) continue;
					EDIFPortInst epr = cellInst.getPortInst(portInst.getPortInstNameFromPort());
					if(epr == null || epr.getNet() == null) continue;
					String hierName = EDIFTools.getHierarchicalRootFromPinName(curr.getHierarchicalInstName());
					q.add(new EDIFHierNet(hierName, epr.getNet()));
				}else if(portInst.getCellInst().getCellType().isPrimitive()){
					// We found a sink
					sinks.add(new EDIFHierPortInst(curr.getHierarchicalInstName(),portInst));
					continue;
				}else{
					// Going down in hierarchy
					EDIFNet internalNet = portInst.getInternalNet();
					if(internalNet == null) continue;
					String hierName = curr.getHierarchicalInstName() + EDIFTools.EDIF_HIER_SEP + portInst.getCellInst().getName();
					q.add(new EDIFHierNet(hierName,internalNet));
				}
			}
			
		}
		
		return sinks;
	}
	
	/**
	 * @param netlist
	 * @param cellInstMap 
	 * @return
	 */
	public HashMap<String, EDIFNet> generateEDIFNetMap(HashMap<String, EDIFCellInst> cellInstMap) {
		HashMap<String,EDIFNet> map = new HashMap<String, EDIFNet>();
		
		Queue<EDIFHierCellInst> toProcess = new LinkedList<EDIFHierCellInst>();
	
		// Add nets at the very top level to start
		for(EDIFNet net : getTopCell().getNets()){
			map.put(net.getName(), net);
		}
		
		Collection<EDIFCellInst> topInstances = getTopCellInst().getCellType().getCellInsts(); 
		if(topInstances != null){
			for(EDIFCellInst i : topInstances){
				toProcess.add(new EDIFHierCellInst("",i));			
			}			
		}
				
		while(!toProcess.isEmpty()){
			EDIFHierCellInst curr = toProcess.poll();			
			String name = curr.getHierarchicalInstName() + curr.getInst().getName();
			if(curr.getInst().getCellType().getNets() == null) continue;
			for(EDIFNet net : curr.getInst().getCellType().getNets()){
				map.put(name + "/" + net.getName(), net);
				//System.out.println("NET: " + name + "/" + net.getOldName());
			}
			String parentName = curr.getHierarchicalInstName() + curr.getInst().getName() + "/";
			if(curr.getInst().getCellType().getCellInsts()==null) continue;
			for(EDIFCellInst i : curr.getInst().getCellType().getCellInsts()){
				toProcess.add(new EDIFHierCellInst(parentName, i));
			}
		
		}
		return map;
	}

	/**
	 * This will be removed in the next release.  
	 * Consider using {@link EDIFCell#getPortMap()} instead
	 * @deprecated
	 * @return
	 */
	public HashMap<String,EDIFPort> generateEDIFPortMap(){
		HashMap<String,EDIFPort> map = new HashMap<String, EDIFPort>(); 
		for(EDIFPort port : getTopCellInst().getCellType().getPorts()){
			if(port.isBus()){
				for(int idx=0; idx < port.getWidth(); idx++){
					map.put(port.getName() + "["+idx+"]",port);
				}
			}else{
				map.put(port.getName(),port);
			}
		}
		return map;
	}

	/**
	 * Identify primitive cell instances in EDIF netlist
	 * @param edif The environment to look through
	 * @return A map of hierarchical names (not including top-level name) 
	 *         to EdifCellInstances that use primitives in the library
	 */
	public HashMap<String,EDIFCellInst> generateCellInstMap(){
		HashMap<String,EDIFCellInst> primitiveInstances = new HashMap<String, EDIFCellInst>();
	
		Queue<EDIFHierCellInst> toProcess = new LinkedList<EDIFHierCellInst>();
		Collection<EDIFCellInst> topInstances = getTopCellInst().getCellType().getCellInsts(); 
		if(topInstances != null){
			for(EDIFCellInst i : topInstances){
				toProcess.add(new EDIFHierCellInst("",i));			
			}			
		}
		
		while(!toProcess.isEmpty()){
			EDIFHierCellInst curr = toProcess.poll();
			if(curr.getInst().getCellType().isPrimitive()){
				String name = curr.getHierarchicalInstName() + curr.getInst().getName();
				primitiveInstances.put(name, curr.getInst());
			}else{
				String parentName = curr.getHierarchicalInstName() + curr.getInst().getName()+ "/"; 
				if(curr.getInst().getCellType().getCellInsts() == null) {
					//System.out.println("No instances for cell type: " + curr.inst.getCellType());
					continue;
				}
				for(EDIFCellInst i : curr.getInst().getCellType().getCellInsts()){
					toProcess.add(new EDIFHierCellInst(parentName, i));
				}
			}
		}
	
		return primitiveInstances;
	}

	private static Set<String> getAllDecendantCellTypes(EDIFCell c) {
		Set<String> types = new HashSet<>();
		
		Queue<EDIFCell> q = new LinkedList<>();
		q.add(c);
		while(!q.isEmpty()) {
			EDIFCell curr = q.poll();
			types.add(curr.getName());
			for(EDIFCellInst i : curr.getCellInsts()) {
				q.add(i.getCellType());
			}
		}
		
		return types;
	}
	
	/**
	 * Expands macro primitives into a native-compatible implementation.
	 * In Vivado, some non-native unisims are expanded or transformed
	 * into one or more native unisims to target the architecture while
	 * supporting the functionality of the macro unisim.  When writing out
	 * EDIF in Vivado, these primitives are collapsed back down to their
	 * primitive state.  This method compensates for this behavior by expanding
	 * the macro primitives. As an example, IBUF => IBUF (IBUFCTRL, IBUF) for 
	 * UltraScale devices.
	 * @param series The architecture series targeted by this netlist.
	 */
	public void expandMacroUnisims(Series series) {
		EDIFLibrary macros = Design.getMacroPrimitives(series);
		EDIFLibrary netlistPrims = getHDIPrimitivesLibrary(); 
		
		// Find the macro primitives to replace
		Set<String> toReplace = new HashSet<String>();
		for(EDIFCell c : netlistPrims.getCells()) {
			if(macros.containsCell(c.getName())) {
				toReplace.addAll(getAllDecendantCellTypes(macros.getCell(c.getName())));
			}
		}
		
		// Replace macro primitives in library and import pre-requisite cells if needed
		for(String cellName : toReplace) {
			if(macroExpandExceptionMap.containsKey(cellName)) {
				cellName = macroExpandExceptionMap.get(cellName);
			}
			EDIFCell removed = netlistPrims.removeCell(cellName);
			if(removed == null) {
				primsToRemoveOnCollapse.add(cellName);
			}
			EDIFCell toAdd = macros.getCell(cellName);
			if(toAdd == null) {
				toAdd = Design.getUnisimCell(Unisim.valueOf(cellName));
			}
			// Add copy to prim library to avoid destructive changes when collapsed
			new EDIFCell(netlistPrims, toAdd);
		}
		
		// Update all cell references to macro versions
		for(EDIFLibrary lib : getLibraries()) {
			boolean isHDILib = lib.isHDIPrimitivesLibrary(); 
			for(EDIFCell cell : lib.getCells()) { 
				for(EDIFCellInst inst : cell.getCellInsts()) {
					String cellName = inst.getCellType().getName();
					if(toReplace.contains(cellName)) {
						if(!isHDILib) {
							cellName = macroExpandExceptionMap.getOrDefault(cellName, cellName); 
						}
						EDIFCell newCell = netlistPrims.getCell(cellName);
						inst.setCellType(newCell);
						for(EDIFPortInst portInst : inst.getPortInsts()) {
							String portName = portInst.getPort().getBusName();
							portInst.setPort(newCell.getPort(portName));
						}
					}
				}
			}
		}
	}
	
	/**
	 * Collapses any macro primitives back into their primitive state.  
	 * Performs the opposite of {@link EDIFNetlist#expandMacroUnisims(Series)}.
	 * @param series The architecture series targeted by this netlist.
	 */
	public void collapseMacroUnisims(Series series) {
		EDIFLibrary macros = Design.getMacroPrimitives(series);
		EDIFLibrary prims = getHDIPrimitivesLibrary();
		ArrayList<EDIFCell> reinsert = new ArrayList<EDIFCell>();
		for(EDIFCell cell : prims.getCells()) {
			if(macros.containsCell(cell.getName())) {
				cell.makePrimitive();
				if(macroCollapseExceptionMap.containsKey(cell.getName())) {
					cell.rename(macroCollapseExceptionMap.get(cell.getName()));
					reinsert.add(cell);
				}
			}
		}
		for(EDIFCell cell : reinsert) {
			prims.removeCell(cell);
			prims.addCell(cell);
		}
		
		for(String name : primsToRemoveOnCollapse) {
			prims.removeCell(name);
		}
	}
	
	/**
	 * Keeps track of the original source directory from where this EDIF file was loaded. 
	 * @return Original directory path from where the EDIF file was loaded
	 */
	public String getOrigDirectory() {
		return origDirectory;
	}

	protected void setOrigDirectory(String origDirectory) {
		this.origDirectory = origDirectory;
	}

	/**
	 * Gets the list of EDN filenames that were present in the original directory where the EDIF
	 * file was loaded from.  These may be important when loading a netlist/checkpoint back into 
	 * Vivado.
	 * @return A list of EDN filenames that may populate encrypted cells within the netlist.
	 */
	public String[] getEncryptedCells() {
		return encryptedCells;
	}

	protected void setEncryptedCells(String[] encryptedCells) {
		this.encryptedCells = encryptedCells;
	}

	public static void main(String[] args) throws FileNotFoundException {
		CodePerfTracker t = new CodePerfTracker("EDIF Import/Export", true);
		t.start("Read EDIF");
		EDIFParser p = new EDIFParser(args[0]);
		EDIFNetlist n = p.parseEDIFNetlist();
		t.stop().start("Export EDIF");
		n.exportEDIF(args[1]);
		t.stop().printSummary();
	}
}
