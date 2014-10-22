/*
 * Copyright (c) 2013-2014 TIBCO Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.tibco.bw.maven.packager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.settings.Settings;

import com.tibco.bw.maven.packager.utils.BWFileUtils;
import com.tibco.bw.maven.packager.utils.BWProjectUtils;
import com.tibco.bw.maven.packager.utils.ProcessExecutor;

/**
 * The Mojo to Install the BW EAR. This Mojo will do 
 *  1. Copy the BW EAR to the local repository. This is required because the BW Application project is a "aggregator" project of type "pom". 
 *  Hence the Custom Artifacts generated by this Project doesn't get installed to the local repository. So this Mojo will take care of that
 *  
 *  2. Deploy the BW EAR to the BW Admin.
 *  This Mojo will also deploy the BW Ear to the BW Admin.
 * 
 * @author Ashutosh
 *
 * @version 1.0
 */

@Mojo( name="bw-installer", defaultPhase=LifecyclePhase.INSTALL  )
@Execute(goal="bw-installer", phase= LifecyclePhase.INSTALL)
public class BWEARInstaller  extends AbstractMojo
{
	@Parameter( property="project.build.directory")
    protected File outputDirectory;
    
	@Parameter( property="project.basedir")
	private File projectBasedir;
	
    @Component
    private MavenSession session;

    @Component
    private MavenProject project;

    @Component
    private MojoExecution mojo;


    @Component
    private ProjectBuilder builder;

    @Component
    private Settings settings;
	
    @Component
    protected ArtifactInstaller installer;
    
    @Parameter( property = "localRepository", required = true, readonly = true )
    protected ArtifactRepository localRepository;
    
	
	@Parameter (property="tibcohome")
	private String tibcoHome;
	
	@Parameter( property="bw.version")
	private String bwVersion;
	
	@Parameter( property="domain")
	private String domain;

	@Parameter( property="appspace")
	private String appspace;

	@Parameter( property="appnode")
	private String appnode;

	@Parameter( property="domainDesc")
	private String domainDesc;

	@Parameter( property="appspaceDesc")
	private String appspaceDesc;

	@Parameter( property="appnodeDesc")
	private String appnodeDesc;
	
	@Parameter( property="osgiport")
	private String osgiport;
	
	@Parameter( property="httpport")
	private String httpport;

	@Parameter( property="profile")
	private String profile;

	@Parameter( property="agent")
	private String agent;

	@Parameter( property="deployToAdmin")
	private String deployToAdmin;

	@Parameter( property="redeployear")
	private String redeployear;
	
	private String startAppNode = "true";
	
	private String earLoc;

	private String earName;
    
	private String applicationName;
    
	private String applicationVersion;

	public String adminExec;

    File bwAdminHome;
    
    /**
     * The execute method.
     * 
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    public void execute() throws MojoExecutionException, MojoFailureException 
	{
		try {
			install();
			
		} catch (Exception e)
		{
			getLog().error( e );
		}
	}
    
    /**
     * Installs the BW EAR to the Local Repository.
     * Deploys the BW EAR to the BW Admin. 
     * @throws Exception
     */
	public void install() throws Exception
	{

		tibcoHome = project.getProperties().getProperty("tibco.home");
		bwVersion = project.getProperties().getProperty("bw.version");

		File [] files = BWFileUtils.getFilesForType(outputDirectory, ".ear");
		if( files.length == 0 )
		{
			throw new Exception( "EAR file not found for the Application" );
		}
		
		deriveEARInformation(files[0]);
		
		bwAdminHome = BWProjectUtils.getBWAdminHome(tibcoHome, bwVersion);
		
		adminExec = BWProjectUtils.getAdminExecutable();
		
		installToRepo(files[0]);
		
		if("true".equals(deployToAdmin))
		{
			installToAdmin();
		}

	}
	
	/**
	 * Deploys the BW EAR file to BWAdmin.
	 * 
	 * It will first check existence of Domain, AppSpace, AppNode and then Create and Deploy accordingly.
	 * 
	 * @throws Exception
	 */
	private void installToAdmin() throws Exception
	{
		//Check if Domain Exists. Else create new Domain.
		boolean domainExists = domainExists();
		if( !domainExists )
		{
			createDomain();
		}
		
		//Check if AppSpace exists. Else create new Appspace.
		boolean appSpaceExists =  domainExists ? appSpaceExists() : false ;
		if( !appSpaceExists )
		{
			createAppSpace();
		}
		
		//Check if AppNode exists else then create new AppNode.
		boolean appNodeExists = appSpaceExists ? appNodeExists() : false;
		if( !appNodeExists )
		{
			createAppNode();
		}

		//Start the AppNode.
		if( "true".equals(startAppNode) )
		{
			startAll();	
		}
		
		//If Application Exists then Undeploy. 
		boolean aapplicationExists = appNodeExists ? applicationExists() : false ;
		if ( aapplicationExists && "true".equals(redeployear ) ) 
		{
			undeployApplication();
		}
		
		//Upload the EAR
		upload();
		
		//Deploy the EAR File.
		deploy();

	}
	
	/**
	 * Installs the EAR file to the Local Maven Repository.
	 *
	 * @param file the BW Ear file.
	 * 
	 * @throws Exception
	 * 
	 */
	private void installToRepo( File file ) throws Exception
	{
		File artifactPath = new File ( new File ( localRepository.getBasedir()) ,  localRepository.pathOf( project.getArtifact()));
		FileUtils.copyFile( file , new File (artifactPath.getParentFile() , file.getName()));
		installer.install(file, project.getArtifact() , localRepository);

	}
	
	/**
	 * Derives the EAR information for deployment.
	 *
	 * @param file the EAR File.
	 */
	private void deriveEARInformation(File file) 
	{
		earLoc = file.getAbsolutePath();
		earLoc = earLoc.replace("\\", "/");
		
		earName = file.getName();
		
		applicationName = FilenameUtils.removeExtension(earName);
		
	}
	
	
	/**
	 * Create the Domain.
	 * 	
	 * @throws Exception
	 */
	private void createDomain() throws Exception
	{
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec); 	
		list.add("create");
		
		list.add("-lax");
		
		if(domainDesc != null && !"".equals(domainDesc.trim()))
		{
			list.add("-descr");
			list.add( domainDesc );
		}
		
		list.add("domain");
		list.add( domain );
			
		executeCommand(list);
	}

	/**
	 * Create the Appspace
	 * 
	 * @throws Exception
	 */
	private void createAppSpace() throws Exception
	{
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		list.add("create");
		
		list.add("-lax");
		
		list.add("-domain");
		list.add( domain );

		list.add("-minNodes");
		list.add( "1" );

		 
		if(appspaceDesc != null && !"".equals(appspaceDesc.trim()))
		{
			list.add("-descr");
			list.add( appspaceDesc );
		}
		
		
		list.add("appspace");
		list.add( appspace );
		
			
		executeCommand(list );
	}

	/**
	 * Create the AppNode
	 * 
	 * @throws Exception
	 */
	private void createAppNode() throws Exception
	{
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		list.add("create");
		
		list.add("-lax");
		
		list.add("-domain");
		list.add( domain );
		
		list.add("-appspace");
		list.add( appspace );
		
		if(appnodeDesc != null && !"".equals(appnodeDesc.trim()))
		{
			list.add("-descr");
			list.add( appnodeDesc );
		}
		
		if( osgiport != null && !"".equals(osgiport))
		{
			list.add("-osgiPort");
			list.add( osgiport);			
		}
		
		list.add("-httpPort");
		list.add( httpport );

		if( agent != null && !"".equals(agent) )
		{
			list.add("-agent");
			list.add( agent );
		}
		
		list.add("appnode");
		list.add( appnode );		
			
		executeCommand(list);
	    
	}

	/**
	 * Start the Appspace and the AppNode.
	 * 
	 * @throws Exception
	 */
	private void startAll() throws Exception
	{
		final List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		
		list.add("start");
		
		list.add("-domain");
		list.add( domain );
		
		list.add("appspace");
		list.add(appspace);
		
//		list.add("appnode ");
//		list.add( appnode );		

		executeCommand(list );
	
	}

	/**
	 * Upload the EAR file to the Admin.
	 * 
	 * @throws Exception
	 */
	private void upload() throws Exception
	{
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		
		list.add("upload");
		
		list.add("-replace");
		
		list.add("-domain");
		list.add(domain);
		
		list.add(earLoc);
		
		executeCommand(list );
		
	}

	/**
	 * Deploy the EAR file to the Admin.
	 * 
	 * @throws Exception
	 * 
	 */
	private void deploy() throws Exception
	{
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		
		list.add("deploy");
		
		list.add("-domain");
		list.add(domain);
		
		list.add("-appspace");
		list.add(appspace);
		
		if(profile != null && !"".equals(profile.trim()))
		{
			list.add("-p");
			list.add(profile);			
		}
		 
		list.add("-as");
		
		list.add(earName);

		executeCommand(list);

	}
	
	/**
	 * Check if the Domain exists.
	 * 
	 * @return true if the Domain exists.
	 * 
	 * @throws Exception
	 */
	private boolean domainExists() throws Exception
	{
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		
		list.add("show");
		list.add("domains");
		
		String domains = executeCommand(list );
		
		if(domains.indexOf(domain + "\u0020") != -1)
		{
			return true;
		}
		else
		{
			return false;
		}
	}
	
	/**
	 * Check if the AppSpace exists. 
	 * 
	 * @return true if the Appspace exists.
	 * 
	 * @throws Exception
	 */
	private boolean appSpaceExists() throws Exception
	{
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		
		list.add("show");
		
		list.add("-domain");
		list.add(domain);
		
		list.add("appspaces");
		
		String appspaces = executeCommand(list );

		if(appspaces.indexOf(appspace) != -1)
		{
			return true; 
		}
		else
		{
			return false;
		}
		

	}

	/**
	 * Checks if the AppNode exists. If yes check the AppNode running status as well.
	 * 
	 * @return true if the AppNode exists.
	 * 
	 * @throws Exception
	 */
	private boolean appNodeExists() throws Exception
	{
		boolean appNodeExists = false;
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		
		list.add("show");
		
		list.add("-domain");
		list.add(domain);
		
		list.add("-appspace");
		list.add(appspace);		
		
		list.add("appnodes");
		
		String appnodes = executeCommand(list );
		
		if(appnodes.indexOf(appnode) != -1)
		{
			appNodeExists = true;
		}

		else
		{
			appNodeExists = false;
		}
		
		if(appnodes.indexOf("Running") != -1)
		{
			startAppNode = "false"; 
		}
		else
		{
			startAppNode = "true";
		}
		
		return appNodeExists;
		
	}


	/**
	 * Checks if the Application exists in the given Domain, Appspace combination.
	 * 
	 * @return true if the Application exists.
	 * 
	 * @throws Exception
	 */
	private boolean applicationExists() throws Exception
	{
		boolean applicationExists = false;
		
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		
		list.add("show");
		
		list.add("-domain");
		list.add(domain);
		
		list.add("-appspace");
		list.add(appspace);
		
		list.add("applications");
		
		String applications = executeCommand(list);

		if(applications.indexOf(applicationName) != -1)
		{

			applicationExists = true;

			//Calculates the Existing Application Version. The Application version is between the Application name and Appspace.
			int versionPrevIndex = applications.indexOf(applicationName);
			int versionNextIndex = applications.indexOf(appspace);
			
			applicationVersion = applications.substring(versionPrevIndex + applicationName.length() + 1 , versionNextIndex -1 ).trim();
			
		}
		else
		{
			applicationExists = false;
		}
		
		return applicationExists;
		
	}
	
	/**
	 * Undeploys the existing Application.
	 * 
	 * @throws Exception
	 */
	private void undeployApplication() throws Exception
	{
		List<String> list = new ArrayList<String>();
        
		list.add( bwAdminHome + adminExec);
		
		list.add("undeploy");
		
		list.add("-domain");
		list.add(domain);
		
		list.add("-appspace");
		list.add(appspace);
		
		list.add("application");
		
		list.add(applicationName);
		
		list.add(applicationVersion);

		executeCommand ( list );

	}

	/**
	 * Executes the given command on the Admin Home.
	 * 
	 * @param params the Commandline Parameter list
	 * 
	 * @return the Execution status.
	 * 
	 * @throws Exception
	 */
	private String executeCommand(List<String> params ) throws Exception 
	{
		ProcessExecutor executor = new ProcessExecutor(bwAdminHome.getAbsolutePath() , getLog() );
		return executor.executeProcess(params);
	}

}
