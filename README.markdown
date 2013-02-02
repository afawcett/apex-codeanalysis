Apex Code Analysis Tool using Tooling API and Canvas
====================================================

This tool utilises the new **Spring'13 Tooling API** and the **Canvas** technology to fill a gap in the Apex developers toolkit. That being the ability to perform analysis on the Apex code they are developing. This initial prototype shows how the SymbolTable information can be used to list unused Apex methods. More information and background to this can be found at my blog [here](http://andrewfawcett.wordpress.com/2013/02/02/spring13-clean-apex-code-with-the-tooling-api). For more information on how to run and deploy this sample, please refer to the [Force.com Canvas Developers Guide](http://www.salesforce.com/us/developer/docs/platform_connect/index.htm).

Generating the Tooling API SOAP Client Proxies (with JAX-WS) and Maven
---------------------------------------------------------------------------

In order to consume the Tooling API in Java I chose the SOAP protocol, since Java is a type safe language and interacting with the data types and operations of the API is much easier using Java's native features. These are the steps I took to accomplish this in Maven, you may want to use a different tool or approach.

- Downloaded the Tooling API WSDL (from the Setup > Develop > API page)
- Edited my **pom.xml** to plugin the **JAX-WS xsimport** [tool](http://jax-ws-commons.java.net/jaxws-maven-plugin/wsimport-mojo.html) and configure it.
- As Salesforce schema types use the minOccurs="0" and nillable="true" convention, this resulted in less desirable (but techncially accurate) generated code to support this convention. As such I created a **/bindings/bindings.xml** file to customise the generated code to produce more typical get/set methods.
- The WSDL available (pre-spring'13 launch) did appear to have some issues / missing elements. 
  - I needed to add the SessionHeader soap header declaration to some of the operations I wanted (others had it). 
  - I also needed to remove 'id' element declarations that appeared to be duplicates of what would be inherited from the SObject base schema type in the WSDL. This caused the generated code to stop generating get/set methods and fall back to name/value pair convention.

I am not sure if the Salesforce WSC compiler tool would have had these issues, thats something I want to look into. However for now the JAX-WS tool does work great insight a Maven build project once the above is sorted.
