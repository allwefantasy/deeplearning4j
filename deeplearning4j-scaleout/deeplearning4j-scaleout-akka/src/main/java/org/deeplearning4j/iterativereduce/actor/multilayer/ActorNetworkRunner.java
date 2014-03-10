package org.deeplearning4j.iterativereduce.actor.multilayer;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.datasets.iterator.DataSetIterator;
import org.deeplearning4j.iterativereduce.actor.core.ClusterListener;
import org.deeplearning4j.iterativereduce.actor.core.actor.BatchActor;
import org.deeplearning4j.iterativereduce.actor.core.actor.ModelSavingActor;
import org.deeplearning4j.iterativereduce.actor.util.ActorRefUtils;
import org.deeplearning4j.iterativereduce.actor.util.PortTaken;
import org.deeplearning4j.nn.BaseMultiLayerNetwork;
import org.deeplearning4j.scaleout.conf.Conf;
import org.deeplearning4j.scaleout.conf.DeepLearningConfigurable;
import org.deeplearning4j.scaleout.iterativereduce.multi.UpdateableImpl;
import org.deeplearning4j.scaleout.zookeeper.ZooKeeperConfigurationRegister;
import org.deeplearning4j.scaleout.zookeeper.ZooKeeperRunner;
import org.jblas.DoubleMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.concurrent.Future;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.ActorSystem;
import akka.actor.Address;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.contrib.pattern.ClusterClient;
import akka.contrib.pattern.ClusterSingletonManager;
import akka.contrib.pattern.DistributedPubSubExtension;
import akka.contrib.pattern.DistributedPubSubMediator;
import akka.dispatch.Futures;
import akka.dispatch.OnComplete;
import akka.routing.RoundRobinPool;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
/**
 * Controller for coordinating model training for a neural network based
 * on parameters across a cluster for akka.
 * @author Adam Gibson
 *
 */
public class ActorNetworkRunner implements DeepLearningConfigurable,Serializable {


	private static final long serialVersionUID = -4385335922485305364L;
	private transient ActorSystem system;
	private Integer epochs;
	private UpdateableImpl result;
	private ActorRef mediator;
	private BaseMultiLayerNetwork startingNetwork;
	private static Logger log = LoggerFactory.getLogger(ActorNetworkRunner.class);
	private static String systemName = "ClusterSystem";
	private String type = "master";
	private Address masterAddress;
	private DataSetIterator iter;
	protected ActorRef masterActor;
	private transient ScheduledExecutorService exec;

	/**
	 * Master constructor
	 * @param type the type (worker)
	 * @param iter the dataset to use
	 * @param startingNetwork a starting neural network
	 */
	public ActorNetworkRunner(String type,DataSetIterator iter,BaseMultiLayerNetwork startingNetwork) {
		this.type = type;
		this.iter = iter;
		this.startingNetwork = startingNetwork;
	}

	/**
	 * Master constructor
	 * @param type the type (worker)
	 * @param iter the dataset to use
	 * @param startingNetwork a starting neural network
	 */
	public ActorNetworkRunner(String type,DataSetIterator iter) {
		this(type,iter,null);
	}


	/**
	 * Master constructor
	 * @param type the type (worker)
	 * @param iter the dataset to use
	 */
	public ActorNetworkRunner(DataSetIterator iter) {
		this("master",iter,null);
	}


	/**
	 * Master constructor
	 * @param type the type (worker)
	 * @param iter the dataset to use
	 */
	public ActorNetworkRunner(DataSetIterator iter,BaseMultiLayerNetwork startingNetwork) {
		this("master",iter,startingNetwork);
	}

	/**
	 * The worker constructor
	 * @param type the type to use
	 * @param address the address of the master
	 */
	public ActorNetworkRunner(String type,String address) {
		this.type = type;
		URI u = URI.create(address);
		masterAddress = Address.apply(u.getScheme(), u.getUserInfo(), u.getHost(), u.getPort());
	}




	public ActorNetworkRunner() {
		super();
	}




	/**
	 * Start a backend with the given role
	 * @param joinAddress the join address
	 * @param role the role to start with
	 * @param c the neural network configuration
	 * @return the actor for this backend
	 */
	public Address startBackend(Address joinAddress, String role,Conf c,DataSetIterator iter) {
		final ActorSystem system = ActorSystem.create(systemName);
		ActorRefUtils.addShutDownForSystem(system);

		system.actorOf(Props.create(ClusterListener.class));

		ActorRef batchActor = system.actorOf(Props.create(BatchActor.class,iter),"batch");

		log.info("Started batch actor");

		Props masterProps = startingNetwork != null ? MasterActor.propsFor(c,batchActor,startingNetwork) : MasterActor.propsFor(c,batchActor);

		/*
		 * Starts a master: in the active state with the poison pill upon failure with the role of master
		 */
		final Address realJoinAddress = (joinAddress == null) ? Cluster.get(system).selfAddress() : joinAddress;

		if(exec == null)
			exec = Executors.newScheduledThreadPool(2);


		Cluster cluster = Cluster.get(system);
		cluster.join(realJoinAddress);

		exec.schedule(new Runnable() {

			@Override
			public void run() {
				Cluster cluster = Cluster.get(system);
				cluster.publishCurrentClusterState();
			}

		}, 10, TimeUnit.SECONDS);

		masterActor = system.actorOf(ClusterSingletonManager.defaultProps(masterProps, "master", PoisonPill.getInstance(), "master"));

		log.info("Started master with address " + realJoinAddress.toString());
		c.setMasterAbsPath(ActorRefUtils.absPath(masterActor, system));
		log.info("Set master abs path " + c.getMasterAbsPath());

		return realJoinAddress;
	}

	


	@Override
	public void setup(final Conf conf) {



		system = ActorSystem.create(systemName);
		ActorRefUtils.addShutDownForSystem(system);
		mediator = DistributedPubSubExtension.get(system).mediator();

		epochs = conf.getPretrainEpochs();
		if(type.equals("master")) {

			if(iter == null)
				throw new IllegalStateException("Unable to initialize no dataset to train");

			log.info("Starting master");
			masterAddress  = startBackend(null,"master",conf,iter);


			try {
				Thread.sleep(60000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
					this), mediator);

			log.info("Starting model saver");
			system.actorOf(Props.create(ModelSavingActor.class,",model-saver"));


			//MAKE SURE THIS ACTOR SYSTEM JOINS THE CLUSTER;
			//There is a one to one join to system requirement for the cluster
			Cluster.get(system).join(masterAddress);
			//store it in zookeeper for service discovery
			conf.setMasterUrl(getMasterAddress().toString());
			conf.setMasterAbsPath(ActorRefUtils.absPath(masterActor, system));
		
			ActorRefUtils.registerConfWithZooKeeper(conf, system);
			
			log.info("Setup master with epochs " + epochs);
		}

		else {

			Conf c = conf.copy();
			Cluster cluster = Cluster.get(system);
			cluster.join(masterAddress);

			startWorker(masterAddress,c);

			log.info("Setup worker nodes");
		}



	}


	public  void startWorker(final Address contactAddress,Conf conf) {
		// Override the configuration of the port
		Config conf2 = ConfigFactory.parseString(String.format("akka.cluster.seed-nodes = [\"" + contactAddress.toString() + "\"]")).
				withFallback(ConfigFactory.load());
		final ActorSystem system = ActorSystem.create(systemName,conf2);
		ActorRefUtils.addShutDownForSystem(system);

		system.actorOf(Props.create(ClusterListener.class));
		log.info("Attempting to join node " + contactAddress);
		log.info("Starting workers");
		Set<ActorSelection> initialContacts = new HashSet<ActorSelection>();
		initialContacts.add(system.actorSelection(contactAddress + "/user/"));

		RoundRobinPool pool = new RoundRobinPool(Runtime.getRuntime().availableProcessors());

		ActorRef clusterClient = system.actorOf(ClusterClient.defaultProps(initialContacts),
				"clusterClient");



		Props p = pool.props(WorkerActor.propsFor(clusterClient,conf));
		system.actorOf(p, "worker");

		Cluster cluster = Cluster.get(system);
		cluster.join(contactAddress);

		log.info("Worker joining cluster");


	}

	
	public void train(List<Pair<DoubleMatrix,DoubleMatrix>> list) {
		log.info("Publishing to results for training");
		//wait for cluster to be up
		try {
			log.info("Waiting for cluster to go up...");
			Thread.sleep(30000);
			log.info("Done waiting");
		} catch (InterruptedException e1) {
			Thread.currentThread().interrupt();
		}

		//ensure the trainer is known so the next iteration can happen
		mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
				this), mediator);
		log.info("Started pipeline");
		//start the pipeline
		mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
				list), mediator);

		log.info("Published results");
	}



	public void train() {
		if(iter.hasNext())
			train(iter.next());

		else
			log.warn("No data found");
	}

	public void train(Pair<DoubleMatrix,DoubleMatrix> input) {
		train(new ArrayList<>(Arrays.asList(input)));

	}



	public void train(DoubleMatrix input,DoubleMatrix labels) {
		train(new Pair<DoubleMatrix,DoubleMatrix>(input,labels));
	}




	public UpdateableImpl getResult() {
		return result;
	}


	public Address getMasterAddress() {
		return masterAddress;
	}



}
