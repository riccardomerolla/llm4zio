package agents

import models.{ AgentInfo, AgentType }

object AgentRegistry:
  val builtInAgents: List[AgentInfo] = List(
    AgentInfo(
      name = "cobolDiscovery",
      displayName = "COBOL Discovery",
      description = "Scans source directories and catalogs COBOL and related files.",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = List("discovery", "inventory", "cobol"),
    ),
    AgentInfo(
      name = "cobolAnalyzer",
      displayName = "COBOL Analyzer",
      description = "Performs deep structural analysis of COBOL programs.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("analysis", "cobol", "structure"),
    ),
    AgentInfo(
      name = "businessLogicExtractor",
      displayName = "Business Logic Extractor",
      description = "Extracts business purpose, use cases, and rules from analyses.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("business-logic", "analysis", "rules"),
    ),
    AgentInfo(
      name = "dependencyMapper",
      displayName = "Dependency Mapper",
      description = "Builds dependency graphs between programs and copybooks.",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = List("dependency", "graph", "mapping"),
    ),
    AgentInfo(
      name = "javaTransformer",
      displayName = "Java Transformer",
      description = "Transforms COBOL analyses into a Spring Boot project.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("transformation", "java", "spring"),
    ),
    AgentInfo(
      name = "validationAgent",
      displayName = "Validation Agent",
      description = "Validates generated Java output for correctness and fidelity.",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("validation", "quality", "semantic"),
    ),
    AgentInfo(
      name = "documentationAgent",
      displayName = "Documentation Agent",
      description = "Generates migration documentation, guides, and diagrams.",
      agentType = AgentType.BuiltIn,
      usesAI = false,
      tags = List("documentation", "reports", "diagrams"),
    ),
  )

  def findByName(name: String): Option[AgentInfo] =
    builtInAgents.find(_.name.equalsIgnoreCase(name.trim))
