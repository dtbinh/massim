package massim.scenario.city.data;

import massim.Log;
import massim.RNG;
import massim.config.TeamConfig;
import massim.scenario.city.CityMap;
import massim.scenario.city.data.facilities.*;
import massim.scenario.city.util.Generator;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * State of the world.
 */
public class WorldState {

    private CityMap cityMap;

    private int totalSteps;
    private String mapName;
    private long seedCapital;
    private int randomFail;

    private List<Tool> tools;
    private Map<String, Item> items = new HashMap<>();
    private Map<String, Role> roles = new HashMap<>();

    private Map<String, Facility> facilities = new HashMap<>();
    private Set<Workshop> workshops = new HashSet<>();
    private Set<Dump> dumps = new HashSet<>();
    private Set<ChargingStation> chargingStations = new HashSet<>();
    private Set<Shop> shops = new HashSet<>();
    private Set<Storage> storages = new HashSet<>();

    private Vector<String> teamNames = new Vector<>();
    private Vector<String> agentNames;
    private Map<String, Vector<String>> teamToAgents = new HashMap<>();
    private Map<String, String> agentToTeam = new HashMap<>();
    private Map<String, Entity> agentToEntity = new HashMap<>();
    private Map<Entity, String> entityToAgent = new HashMap<>();
    private Map<Location, Facility> facilityByLocation = new HashMap<>();
    private Map<String, TeamState> teams = new HashMap<>();
    private Map<String, Job> jobs = new HashMap<>();
    private List<Job> newJobs = new Vector<>();

    public WorldState(int steps, JSONObject config, Set<TeamConfig> matchTeams, Generator generator) {

        totalSteps = steps;

        // parse simulation config
        mapName = config.optString("map", "london");
        Log.log(Log.NORMAL, "Configuring scenario map: " + mapName);
        seedCapital = config.optLong("seedCapital", 50000L);
        Log.log(Log.NORMAL, "Configuring scenario seedCapital: " + seedCapital);
        double minLon = config.optDouble("minLon", 0);
        Log.log(Log.NORMAL, "Configuring scenario minLon: " + minLon);
        double maxLon = config.optDouble("maxLon", 0);
        Log.log(Log.NORMAL, "Configuring scenario maxLon: " + maxLon);
        double minLat = config.optDouble("minLat", 0);
        Log.log(Log.NORMAL, "Configuring scenario minLat: " + minLat);
        double maxLat = config.optDouble("maxLat", 0);
        Log.log(Log.NORMAL, "Configuring scenario maxLat: " + maxLat);
        double proximity = config.optDouble("proximity", 0.0002);
        Log.log(Log.NORMAL, "Configuring scenario proximity: " + proximity);
        Location.setProximity(proximity);
        double cellSize = config.optDouble("cellSize", 0.001);
        Log.log(Log.NORMAL, "Configuring scenario cellSize: " + cellSize);
        double centerLat = config.optDouble("centerLat", 0);
        Log.log(Log.NORMAL, "Configuring scenario centerLat: " + centerLat);
        double centerLon = config.optDouble("centerLon", 0);
        Log.log(Log.NORMAL, "Configuring scenario centerLon: " + centerLon);
        Location mapCenter = new Location(centerLon, centerLat);
        randomFail = config.optInt("randomFail", 1);
        Log.log(Log.NORMAL, "Configuring random fail probability: " + randomFail);

        parseRoles(config.optJSONObject("roles"));

        cityMap = new CityMap(mapName, cellSize, minLat, maxLat, minLon, maxLon, mapCenter);

        // store teams
        matchTeams.forEach(team -> {
            teamNames.add(team.getName());
            Vector<String> agNames = team.getAgentNames();
            teamToAgents.put(team.getName(), agNames);
            agNames.forEach(agName -> agentToTeam.put(agName, team.getName()));
            teams.put(team.getName(), new TeamState(seedCapital, team.getName()));
        });
        agentNames = new Vector<>(agentToTeam.keySet());

        // check entity roles
        Vector<String> roleSequence = new Vector<>();
        JSONArray entities = config.optJSONArray("entities");
        if(entities != null){
            for (int i = 0; i < entities.length(); i++) {
                JSONObject entityConf = entities.optJSONObject(i);
                if (entityConf != null){
                    String roleName = entityConf.keys().next();
                    int amount = entityConf.optInt(roleName, 0);
                    for (int j = 0; j < amount; j++){
                        roleSequence.add(roleName);
                    }
                }
            }
        }

        // generate the things
        tools = generator.generateTools(new ArrayList<>(roles.values()));
        generator.generateItems(tools).forEach(i -> items.put(i.getName(), i));
        generator.generateFacilities(new ArrayList<>(items.values()), this).forEach(f -> facilities.put(f.getName(), f));
        facilities.values().forEach(f -> facilityByLocation.put(f.getLocation(), f));
        facilities.values().forEach(f -> {
            if(f instanceof Workshop) workshops.add((Workshop) f);
            else if(f instanceof ChargingStation) chargingStations.add((ChargingStation) f);
            else if(f instanceof Shop) shops.add((Shop) f);
            else if(f instanceof Storage) storages.add((Storage) f);
            else if(f instanceof Dump) dumps.add((Dump) f);
        });

        // draw initial locations
        Location[] initialLocations = new Location[matchTeams.iterator().next().getSize()];
        Set<String> roads = new HashSet<>(Collections.singletonList("roads"));
        for (int i = 0; i < initialLocations.length; i++) {
            initialLocations[i] = cityMap.getRandomLocation(roads, 1000);
        }

        // create entities and map to agents
        matchTeams.forEach(team -> {
            for (int i = 0; i < team.getAgentNames().size(); i++) {
                Entity e = new Entity(roles.get(roleSequence.get(i)), initialLocations[i]);
                agentToEntity.put(team.getAgentNames().get(i), e);
                entityToAgent.put(e, team.getAgentNames().get(i));
            }
        });
    }

    /**
     * Reads role information from the "role" JSON object.
     * @param roles the JSON object hopefully containing some roles
     */
    private void parseRoles(JSONObject roles) {
        if (roles == null){
            Log.log(Log.CRITICAL, "No roles defined");
            return;
        }
        roles.keys().forEachRemaining(roleName -> {
            JSONObject roleJson = roles.optJSONObject(roleName);
            if (roleJson == null) {
                Log.log(Log.ERROR, "Invalid JSON role object.");
            }
            else {
                JSONArray permissionsJson = roleJson.optJSONArray("permissions");
                Set<String> permissions = new HashSet<>();
                if (permissionsJson != null){
                    for (int i = 0; i < permissionsJson.length(); i++) {
                        permissions.add(permissionsJson.optString(i, ""));
                    }
                }
                this.roles.put(roleName, new Role(roleName, roleJson.optInt("speed", 5),
                        roleJson.optInt("battery", 100), roleJson.optInt("load", 100),
                        permissions));
            }
        });
    }

    /**
     * @return a new list of all agents in no particular order
     */
    public List<String> getAgents() {
        return new ArrayList<>(agentNames);
    }

    /**
     * @param agentName the name of an agent
     * @return the entity connected to that agent or null if no such entity exists
     */
    public Entity getEntity(String agentName) {
        return agentToEntity.get(agentName);
    }

    /**
     * @return the total number of steps of the simulation
     */
    public int getSteps() {
        return totalSteps;
    }

    /**
     * @param agentName the name of an agent
     * @return the name of the agent's team or null if no such agent exists
     */
    public String getTeamForAgent(String agentName) {
        return agentToTeam.get(agentName);
    }

    /**
     * @return the name of the map used in this simulation
     */
    public String getMapName() {
        return mapName;
    }

    /**
     * @return the seed capital for this simulation
     */
    public long getSeedCapital() {
        return seedCapital;
    }

    /**
     * @return a new list containing all items of this simulation
     */
    public List<Item> getItems() {
        return new ArrayList<>(items.values());
    }

    /**
     * @param name the name of the facility
     * @return the facility with the given name or null if it does not exist
     */
    public Facility getFacility(String name) {
        return facilities.get(name);
    }

    /**
     * @return the map of this world
     */
    public CityMap getMap(){
        return cityMap;
    }

    /**
     * @param name the name of an item
     * @return the item with the given name or null if no item with that name exists
     */
    public Item getItem(String name) {
        return items.get(name);
    }

    /**
     * @return a set containing all entities in the simulation
     */
    public Set<Entity> getEntities() {
        return new HashSet<>(entityToAgent.keySet());
    }

    /**
     * @param loc a location
     * @return the facility at the given location or null, if there is none
     */
    public Facility getFacilityByLocation(Location loc){
        return facilityByLocation.get(loc);
    }

    /**
     * Retrieves a team.
     * @param name name of the team
     * @return the state of the team or null if no team with that name exists
     */
    public TeamState getTeam(String name){
        return teams.get(name);
    }

    /**
     * @param name name of a job
     * @return the job with the given name or null if no such job exists
     */
    public Job getJob(String name) {
        return jobs.get(name);
    }

    /**
     * Adds the given job to the system (as a new job).
     * The job will not show up until the end of the simulation step.
     */
    public void addJob(Job job){
        newJobs.add(job);
    }

    /**
     * Lets the jobs get names and transfers them to the global job list.
     * This is to ensure that the name of the job does not indicate who posted it.
     */
    public void processNewJobs() {
        RNG.shuffle(newJobs);
        newJobs.forEach(job -> {
            job.acquireName();
            jobs.put(job.getName(), job);
        });
        newJobs.clear();
    }

    /**
     * @return the probability for any action to be random_fail
     */
    public int getRandomFail(){
        return randomFail;
    }

    public String getSimID() {
        // TODO
        return "sim";
    }

    /**
     * @param e an entity in this world
     * @return the name of the agent connected to the entity or null if this entity does not exist
     */
    public String getAgentForEntity(Entity e){
        return entityToAgent.get(e);
    }

    public Set<Dump> getDumps() {
        return dumps;
    }

    public Set<Workshop> getWorkshops() {
        return workshops;
    }

    public Set<ChargingStation> getChargingStations() {
        return chargingStations;
    }

    public Set<Shop> getShops() {
        return shops;
    }

    public Set<Storage> getStorages() {
        return storages;
    }

    /**
     * @return a new list of all team states
     */
    public List<TeamState> getTeams() {
        return new ArrayList<>(teams.values());
    }

    /**
     * @return a new set of all jobs regardless of their state
     */
    public Set<Job> getJobs() {
        return new HashSet<>(jobs.values());
    }

    /**
     * @return a new list containing all existing tools
     */
    public List<Tool> getTools() {
        return new ArrayList<>(tools);
    }
}
