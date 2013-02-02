/**
 * Copyright (c) 2013, FinancialForce.com, inc
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, 
 *   are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, 
 *      this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *      this list of conditions and the following disclaimer in the documentation 
 *      and/or other materials provided with the distribution.
 * - Neither the name of the FinancialForce.com, inc nor the names of its contributors 
 *      may be used to endorse or promote products derived from this software without 
 *      specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES 
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL 
 *  THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 *  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 *  OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 *  OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 *  ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
**/

package canvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.sforce.soap.tooling.*;

/** 
 * This class uses the new Spring'13 Tooling API from Salesforce to determine unused Apex Methods in an org
 * 
 * NOTE: The current version only refers to references made between Apex Classes, further research 
 *       is required to determine if references made view Visualforce bindings are also supported
 *        
 * @author afawcett
 */
public class ToolingAPI {

	public static String getUnusedApexMethods(String input, String secret)
	{
		// Get oAuth token
		CanvasRequest request = SignedRequest.verifyAndDecode(input, secret);
		String oAuthToken = request.getClient().getOAuthToken();
		
		// Connect to Tooling API
		SforceServiceService service = new SforceServiceService();
		SforceServicePortType port = service.getSforceService();
		SessionHeader sessionHeader = new SessionHeader();
		sessionHeader.setSessionId(oAuthToken);

		// Query visible Apex classes (this query does not support querying in packaging orgs)
		ApexClass[] apexClasses = 
			port.query("select Id, Name, Body from ApexClass where NamespacePrefix = null", sessionHeader)
				.getRecords().toArray(new ApexClass[0]);
		
		// Delete existing MetadataContainer?
		MetadataContainer[] containers = 
			port.query("select Id, Name from MetadataContainer where Name = 'UnusedApexMethods'", sessionHeader)
				.getRecords().toArray(new MetadataContainer[0]);
		if(containers.length>0)
			port.delete(Arrays.asList(containers[0].getId()), sessionHeader);
		
		// Create new MetadataContainer
		MetadataContainer container = new MetadataContainer();
		container.setName("UnusedApexMethods");
		List<SaveResult> saveResults = port.create(new ArrayList<SObject>(Arrays.asList(container)), sessionHeader);
		String containerId = saveResults.get(0).getId();
		
		// Create ApexClassMember's and associate them with the MetadataContainer
		List<ApexClassMember> apexClassMembers = new ArrayList<ApexClassMember>();
		for(ApexClass apexClass : apexClasses)
		{
			ApexClassMember apexClassMember = new ApexClassMember();
			apexClassMember.setBody(apexClass.getBody());
			apexClassMember.setContentEntityId(apexClass.getId());
			apexClassMember.setMetadataContainerId(containerId);
			apexClassMembers.add(apexClassMember);
		}
		saveResults = port.create(new ArrayList<SObject>(apexClassMembers), sessionHeader);
		List<String> apexClassMemberIds = new ArrayList<String>();
		for(SaveResult saveResult : saveResults)
			apexClassMemberIds.add(saveResult.getId());			
		
		// Create ContainerAysncRequest to deploy the (check only) the Apex Classes and thus obtain the SymbolTable's
		ContainerAsyncRequest ayncRequest = new ContainerAsyncRequest();
		ayncRequest.setMetadataContainerId(containerId);
		ayncRequest.setIsCheckOnly(true);
		saveResults = port.create(new ArrayList<SObject>(Arrays.asList(ayncRequest)), sessionHeader);
		String containerAsyncRequestId = saveResults.get(0).getId();
		ayncRequest = (ContainerAsyncRequest) 
			port.retrieve("State", "ContainerAsyncRequest", Arrays.asList(containerAsyncRequestId), sessionHeader).get(0);
		while(ayncRequest.getState().equals("Queued"))
		{			
			try {
				Thread.sleep(1 * 1000); // Wait for a second
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			ayncRequest = (ContainerAsyncRequest) 
				port.retrieve("State", "ContainerAsyncRequest", Arrays.asList(containerAsyncRequestId), sessionHeader).get(0);
		}
		
		// Query again the ApexClassMember's to retrieve the SymbolTable's
		ApexClassMember[] apexClassMembersWithSymbols = 
			port.retrieve("Body, ContentEntityId, SymbolTable", "ApexClassMember", apexClassMemberIds, sessionHeader)
				.toArray(new ApexClassMember[0]);
		
		// Map declared methods and external method references from SymbolTable's
		Set<String> declaredMethods = new HashSet<String>(); 
		Set<String> methodReferences = new HashSet<String>();
		for(ApexClassMember apexClassMember : apexClassMembersWithSymbols)
		{
			// List class methods defined and referenced
			SymbolTable symbolTable = apexClassMember.getSymbolTable();
			if(symbolTable==null) // No symbol table, then class likely is invalid
				continue;
			for(Method method : symbolTable.getMethods())
			{
				// Annotations are not exposed currently, following attempts to detect test methods to avoid giving false positives
				if(method.getName().toLowerCase().contains("test") &&
				   method.getVisibility() == SymbolVisibility.PRIVATE && 
				   (method.getReferences()==null || method.getReferences().size()==0))
					continue;					
				// Skip Global methods as implicitly these are referenced
				if( method.getVisibility() == SymbolVisibility.GLOBAL)
					continue;	
				// Bug? (public method from System.Test?) 
				if( method.getName().equals("aot"))
					continue;
				// Add the qualified method name to the list
				declaredMethods.add(symbolTable.getName() + "." + method.getName());
				// Any local references to this method?
				if(method.getReferences()!=null && method.getReferences().size()>0) 
					methodReferences.add(symbolTable.getName() + "." + method.getName());
			}
			// Add any method references this class makes to other class methods
			for(ExternalReference externalRef : symbolTable.getExternalReferences())
				for(ExternalMethod externalMethodRef : externalRef.getMethods())
					methodReferences.add(externalRef.getName() + "." + externalMethodRef.getName());
		}
		
		// List declaredMethods with no external references
		TreeSet<String> unusedMethods = new TreeSet<String>();
		for(String delcaredMethodName : declaredMethods)
			if(!methodReferences.contains(delcaredMethodName))
				unusedMethods.add(delcaredMethodName);
		
		// Render HTML table to display results
		StringBuilder sb = new StringBuilder();		
		sb.append("<table>");
		for(String methodName : unusedMethods)
			sb.append("<tr><td>" + methodName + "</td></tr>");
		sb.append("</table>");		
		return sb.toString();
	}
}
