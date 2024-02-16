/*
 * Copyright 2016-2024 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */

package kotlinx.validation.klib

import java.io.File
import java.nio.file.Files

internal data class Target(val name: String)

internal class LinesProvider(private val lines: Iterator<String>) : Iterator<String> {
    private var nextLine: String? = null

    public fun peek(): String? {
        if (nextLine != null) {
            return nextLine
        }
        if (!lines.hasNext()) {
            return null
        }
        nextLine = lines.next()
        return nextLine
    }

    override fun hasNext(): Boolean {
        return nextLine != null || lines.hasNext()
    }

    override fun next(): String {
        if (nextLine != null) {
            val res = nextLine!!
            nextLine = null
            return res
        }
        return lines.next()
    }
}

private const val MERGED_DUMP_FILE_HEADER = "// Merged KLib ABI Dump"
private const val REGULAR_DUMP_FILE_HEADER = "// Rendering settings:"
private const val COMMENT_PREFIX = "//"
private const val TARGETS_LIST_PREFIX = "// Targets: ["
private const val TARGETS_LIST_SUFFIX = "]"
private const val TARGETS_DELIMITER = ", "
private const val CLASS_DECLARATION_TERMINATOR = "}"
private const val INDENT_WIDTH = 4

private fun String.depth(): Int {
    val indentation = this.takeWhile { it == ' ' }.count()
    require(indentation % INDENT_WIDTH == 0) {
        "Unexpected indentation, should be a multiple of $INDENT_WIDTH: $this"
    }
    return indentation / INDENT_WIDTH
}

private fun parseBcvTargetsLine(line: String): Set<Target> {
    val trimmedLine = line.trimStart(' ')
    check(trimmedLine.startsWith(TARGETS_LIST_PREFIX) && trimmedLine.endsWith(TARGETS_LIST_SUFFIX)) {
        "Not a targets list line: \"$line\""
    }
    return trimmedLine.substring(TARGETS_LIST_PREFIX.length, trimmedLine.length - 1)
        .split(TARGETS_DELIMITER)
        .map { Target(it) }
        .toSet()
}

internal data class KlibAbiDumpFormat(
    val includeTargets: Boolean = true,
    val useGroupAliases: Boolean = false
)

/**
 * A class representing a textual KLib ABI dump, either a regular one, or a merged.
 */
internal class KlibAbiDumpMerger {
    private val targetsMut: MutableSet<Target> = mutableSetOf()
    private val headerContent: MutableList<String> = mutableListOf()
    private val topLevelDeclaration: DeclarationContainer = DeclarationContainer("")

    /**
     * All targets for which this dump contains declarations.
     */
    public val targets: Set<Target> = targetsMut

    public fun loadMergedDump(file: File) {
        require(file.exists()) { "File does not exist: $file" }
        Files.lines(file.toPath()).use {
            mergeFile(emptySet(), LinesProvider(it.iterator()))
        }
    }

    public fun addIndividualDump(target: Target, file: File) {
        require(file.exists()) { "File does not exist: $file" }
        Files.lines(file.toPath()).use {
            mergeFile(setOf(target), LinesProvider(it.iterator()))
        }
    }

    private fun mergeFile(targets: Set<Target>, lines: LinesProvider) {
        val isMergedFile = targets.isEmpty()
        if (isMergedFile) check(this.targetsMut.isEmpty()) { "Merged dump could only be loaded once." }

        lines.checkFileFormat(isMergedFile)

        val bcvTargets = if (isMergedFile) {
            lines.parseTargets()
        } else {
            targets
        }
        val aliases = if (isMergedFile) {
            lines.parseAliases()
        } else {
            emptyMap()
        }

        val header = lines.parseFileHeader()
        if (isMergedFile || this.targetsMut.isEmpty()) {
            headerContent.addAll(header)
        } else if (headerContent != header) {
            throw IllegalStateException("File header doesn't match the header of other files")
        }
        this.targetsMut.addAll(bcvTargets)
        topLevelDeclaration.targets.addAll(bcvTargets)

        // All declarations belonging to the same scope have equal indentation.
        // Nested declarations have higher indentation.
        // By tracking the indentation, we can decide if the line should be added into the current container,
        // to its parent container (i.e., the line represents sibling declaration) or the current declaration ended,
        // and we must pop one or several declarations out of the parsing stack.
        var currentContainer = topLevelDeclaration
        var depth = -1

        while (lines.hasNext()) {
            val line = lines.peek()!!
            // TODO: wrap the line and cache the depth inside that wrapper?
            val lineDepth = line.depth()
            when {
                // The depth is the same as before; we encountered a sibling
                depth == lineDepth -> {
                    currentContainer =
                        lines.parseDeclaration(lineDepth, currentContainer.parent!!, bcvTargets, isMergedFile, aliases)
                }
                // The depth is increasing; that means we encountered child declaration
                depth < lineDepth -> {
                    check(lineDepth - depth == 1) {
                        "The line has too big indentation relative to a previous line\nline: $line\n" +
                                "previous: ${currentContainer.text}"
                    }
                    currentContainer =
                        lines.parseDeclaration(lineDepth, currentContainer, bcvTargets, isMergedFile, aliases)
                    depth = lineDepth
                }
                // Otherwise, we're finishing all the declaration with greater depth compared to the depth of
                // the next line.
                // We won't process a line if it contains a new declaration here, just update the depth and current
                // declaration reference to process the new declaration on the next iteration.
                else -> {
                    while (currentContainer.text.depth() > lineDepth) {
                        currentContainer = currentContainer.parent!!
                    }
                    // If the line is '}' - add it as a terminator to the corresponding declaration, it'll simplify
                    // dumping the merged file back to text format.
                    if (line.trim() == CLASS_DECLARATION_TERMINATOR) {
                        currentContainer.delimiter = line
                        // We processed the terminator char, so let's skip this line.
                        lines.next()
                    }
                    // For the top level declaration depth is -1
                    depth = if (currentContainer.parent == null) -1 else currentContainer.text.depth()
                }
            }
        }
    }

    private fun LinesProvider.parseTargets(): Set<Target> {
        val line = peek()
        require(line != null) {
            "List of targets expected, but there are no more lines left."
        }
        require(line.startsWith(TARGETS_LIST_PREFIX)) {
            "The line should starts with $TARGETS_LIST_PREFIX, but was: $line"
        }
        next()
        return parseBcvTargetsLine(line)
    }

    private fun LinesProvider.parseAliases(): Map<String, Set<Target>> {
        val aliases = mutableMapOf<String, Set<Target>>()
        while (peek()?.startsWith("// Alias: ") == true) {
            val line = next()
            val trimmedLine = line.substring("// Alias: ".length)
            val separatorIdx = trimmedLine.indexOf(" => [")
            if (separatorIdx == -1 || !trimmedLine.endsWith(']')) {
                throw IllegalStateException("Invalid alias line: $line")
            }
            val name = trimmedLine.substring(0, separatorIdx)
            val targets = trimmedLine.substring(
                separatorIdx + " => [".length,
                trimmedLine.length - 1
            )
                .split(",")
                .map { Target(it.trim()) }
                .toSet()
            aliases[name] = targets
        }
        return aliases
    }

    private fun LinesProvider.parseFileHeader(): List<String> {
        val header = mutableListOf<String>()
        while (hasNext()) {
            val next = peek()!!
            if ((next.startsWith(COMMENT_PREFIX) && !next.startsWith(TARGETS_LIST_PREFIX)) || next.isBlank()) {
                header.add(next)
                next()
            } else {
                break
            }
        }
        return header
    }

    private fun LinesProvider.checkFileFormat(isMergedFile: Boolean) {
        val headerLine = if (isMergedFile) {
            next()
        } else {
            peek()!!
        }
        val expectedHeader = if (isMergedFile) {
            MERGED_DUMP_FILE_HEADER
        } else {
            REGULAR_DUMP_FILE_HEADER
        }

        check(headerLine == expectedHeader) {
            val headerStart = if (headerLine.length > 32) {
                headerLine.substring(0, 32) + "..."
            } else {
                headerLine
            }
            "Expected a file staring with \"$expectedHeader\", but the file stats with \"$headerStart\""
        }
    }

    private fun LinesProvider.parseDeclaration(
        depth: Int,
        parent: DeclarationContainer,
        allTargets: Set<Target>,
        isMergedFile: Boolean,
        aliases: Map<String, Set<Target>>
    ): DeclarationContainer {
        val line = peek()!!
        return if (line.startsWith(" ".repeat(depth * INDENT_WIDTH) + TARGETS_LIST_PREFIX)) {
            check(isMergedFile) {
                "Targets declaration should only be a part of merged file, " +
                        "and the current file claimed to be a regular dump file:\n$line"
            }
            next() // skip prefix
            // Target list means that the declaration following it has a narrower set of targets then its parent,
            // so we must use it.
            val targets = parseBcvTargetsLine(line)
            val expandedTargets = targets.flatMap {
                aliases[it.name] ?: listOf(it)
            }.toSet()
            parent.createOrUpdateChildren(next(), expandedTargets)
        } else {
            // That's an ugly part:
            // - for a merged file (isMergedFile==true) we need to use parent declaration targets: if we're in this
            //   branch, no explicit targets were specified, and new declaration targets should be the same as targets
            //   of its parent. We can't use allTargets here, as parent may have a more specific set of targets.
            // - for a single klib dump file, we need to specify the exact target associated with this file and allTargets
            //   must contain exactly one value here.
            parent.createOrUpdateChildren(next(), if (isMergedFile) parent.targets else allTargets)
        }
    }

    fun dump(appendable: Appendable, dumpFormat: KlibAbiDumpFormat = KlibAbiDumpFormat()) {
        val formatter = createFormatter(dumpFormat)
        if (dumpFormat.includeTargets) {
            appendable.append(MERGED_DUMP_FILE_HEADER).append('\n')
            appendable.append(formatter.formatHeader(targets)).append('\n')
        } else {
            require(targets.size == 1) {
                "Can skip target inclusion only if the dump contains a single target, but it contains: $targets"
            }
        }
        headerContent.forEach {
            appendable.append(it).append('\n')
        }
        topLevelDeclaration.children.sortedWith(DeclarationsComparator).forEach {
            it.dump(appendable, targetsMut, dumpFormat, formatter)
        }
    }

    private fun createFormatter(dumpFormat: KlibAbiDumpFormat): KLibsTargetsFormatter {
        return if (dumpFormat.useGroupAliases) {
            for (target in targets) {
                val node = TargetHierarchy.hierarchyIndex[target.name]
                if (node != null && node.allLeafs.size == 1 && node.allLeafs.first() != node.node.name) {
                    throw IllegalStateException(
                        "Can't use target aliases as one of the this dump's targets" +
                                " has the same name as a group in the default targets hierarchy: ${target.name}"
                    )
                }
            }
            return GroupingFormatter(targets)
        } else {
            DefaultFormatter
        }
    }

    /**
     * Remove the [target] from this dump.
     * If some declaration was declared only for [target], it will be removed from the dump.
     */
    fun remove(target: Target) {
        if (!targetsMut.contains(target)) {
            return
        }

        targetsMut.remove(target)
        topLevelDeclaration.remove(target)
    }

    /**
     * Leave only declarations specific to a [target].
     * A declaration is considered target-specific if:
     * 1) it defined for some [targets] subset including [target], but not for all [targets];
     * 2) it defined for all [targets], but contains target-specific child declaration.
     */
    fun retainTargetSpecificAbi(target: Target) {
        if (!targetsMut.contains(target)) {
            targetsMut.clear()
            topLevelDeclaration.children.clear()
            topLevelDeclaration.targets.clear()
            return
        }

        topLevelDeclaration.retainSpecific(target, targetsMut)
        targetsMut.retainAll(setOf(target))
    }

    /**
     * Remove all declarations that are not defined for all [KlibAbiDumpMerger.targets].
     */
    fun retainCommonAbi() {
        topLevelDeclaration.retainCommon(targetsMut)
        if (topLevelDeclaration.children.isEmpty()) {
            targetsMut.clear()
        }
    }

    /**
     * Merge the [other] dump containing declarations for a single target into this dump.
     * The dump [other] should contain exactly one target and this dump should not contain that target.
     */
    fun mergeTargetSpecific(other: KlibAbiDumpMerger) {
        require(other.targetsMut.size == 1) {
            "The dump to merge in should have a single target, but its targets are: ${other.targets}"
        }
        require(other.targetsMut.first() !in targetsMut) {
            "Targets of this dump and the dump to merge into it should not intersect. " +
                    "Common target: ${other.targets.first()}}"
        }

        targetsMut.addAll(other.targetsMut)
        topLevelDeclaration.mergeTargetSpecific(other.topLevelDeclaration)
    }

    /**
     * For each declaration change targets to a specified [targets] set.
     */
    fun overrideTargets(targets: Set<Target>) {
        targetsMut.clear()
        targetsMut.addAll(targets)

        topLevelDeclaration.overrideTargets(targets)
    }
}

/**
 * A class representing a single declaration from a KLib API dump along with all its children
 * declarations.
 */
private class DeclarationContainer(val text: String, val parent: DeclarationContainer? = null) {
    val targets: MutableSet<Target> = mutableSetOf()
    val children: MutableList<DeclarationContainer> = mutableListOf()
    var delimiter: String? = null
    private val childrenCache: MutableMap<String, DeclarationContainer> = mutableMapOf()

    fun createOrUpdateChildren(text: String, targets: Set<Target>): DeclarationContainer {
        val child = childrenCache.computeIfAbsent(text) {
            val newChild = DeclarationContainer(it, this)
            children.add(newChild)
            newChild
        }
        child.targets.addAll(targets)
        return child
    }

    fun dump(
        appendable: Appendable, allTargets: Set<Target>,
        dumpFormat: KlibAbiDumpFormat, formatter: KLibsTargetsFormatter
    ) {
        if (targets != allTargets && dumpFormat.includeTargets) {
            // Use the same indentation for target list as for the declaration itself
            appendable.append(" ".repeat(text.depth() * INDENT_WIDTH))
                .append(formatter.formatDeclarationTargets(targets))
                .append('\n')
        }
        appendable.append(text).append('\n')
        children.sortedWith(DeclarationsComparator).forEach {
            it.dump(appendable, this.targets, dumpFormat, formatter)
        }
        if (delimiter != null) {
            appendable.append(delimiter).append('\n')
        }
    }

    fun remove(target: Target) {
        if (parent != null && !targets.contains(target)) {
            return
        }

        targets.remove(target)
        children.removeIf {
            val shouldRemove = it.targets.contains(target) && it.targets.size == 1
            if (shouldRemove) {
                childrenCache.remove(it.text)
            }
            shouldRemove
        }
        children.forEach { it.remove(target) }
    }

    fun retainSpecific(target: Target, allTargets: Set<Target>) {
        if (parent != null && !targets.contains(target)) {
            children.clear()
            targets.clear()
            return
        }

        children.forEach { it.retainSpecific(target, allTargets) }
        children.removeIf { it.targets.isEmpty() }
        if (targets == allTargets) {
            if (children.isEmpty()) {
                targets.clear()
            } else {
                targets.retainAll(setOf(target))
            }
        } else {
            targets.retainAll(setOf(target))
        }
    }

    fun retainCommon(commonTargets: Set<Target>) {
        if (parent != null && targets != commonTargets) {
            children.clear()
            targets.clear()
            return
        }
        children.forEach { it.retainCommon(commonTargets) }
        children.removeIf { it.targets.isEmpty() }
    }

    fun mergeTargetSpecific(other: DeclarationContainer) {
        targets.addAll(other.targets)
        val newChildren = mutableListOf<DeclarationContainer>()
        other.children.forEach { otherChild ->
            val child = children.find { it.text == otherChild.text }
            if (child != null) {
                child.mergeTargetSpecific(otherChild)
            } else {
                newChildren.add(otherChild)
            }
        }
        children.forEach {
            if (other.targets.first() !in it.targets) {
                it.addTargetRecursively(other.targets.first())
            }
        }
        children.addAll(newChildren)
    }

    private fun addTargetRecursively(first: Target) {
        targets.add(first)
        children.forEach { it.addTargetRecursively(first) }
    }

    fun overrideTargets(targets: Set<Target>) {
        this.targets.clear()
        this.targets.addAll(targets)

        children.forEach { it.overrideTargets(targets) }
    }
}

// TODO: optimize
private object DeclarationsComparator : Comparator<DeclarationContainer> {
    override fun compare(c0: DeclarationContainer, c1: DeclarationContainer): Int {
        return if (c0.targets == c1.targets) {
            c0.text.compareTo(c1.text)
        } else {
            if (c0.targets.size == c1.targets.size) {
                val c0targets = c0.targets.asSequence().map { it.name }.sorted().iterator()
                val c1targets = c1.targets.asSequence().map { it.name }.sorted().iterator()
                var result = 0
                while (c1targets.hasNext() && c0targets.hasNext() && result == 0) {
                    result = c0targets.next().compareTo(c1targets.next())
                }
                result
            } else {
                // the longer the target list, the earlier the declaration would appear
                c1.targets.size.compareTo(c0.targets.size)
            }
        }
    }
}

private interface KLibsTargetsFormatter {
    fun formatHeader(targets: Set<Target>): String

    fun formatDeclarationTargets(targets: Set<Target>): String
}

private object DefaultFormatter : KLibsTargetsFormatter {
    override fun formatHeader(targets: Set<Target>): String {
        return formatDeclarationTargets(targets)
    }

    override fun formatDeclarationTargets(targets: Set<Target>): String {
        return targets.sortedBy { it.name }
            .joinToString(TARGETS_DELIMITER, TARGETS_LIST_PREFIX, TARGETS_LIST_SUFFIX) { it.name }
    }
}

private class GroupingFormatter(allTargets: Set<Target>) : KLibsTargetsFormatter {
    private data class Alias(val name: String, val targets: Set<Target>)

    private val aliases: List<Alias>

    init {
        val aliasesBuilder = mutableListOf<Alias>()
        TargetHierarchy.hierarchyIndex.asSequence()
            // place smaller groups (more specific groups) closer to beginning of the list
            .sortedWith(compareBy({ it.value.allLeafs.size }, { it.key }))
            .forEach {
                // intersect with all targets to use only enabled targets in aliases
                val availableTargets = it.value.allLeafs.map {
                    Target(it)
                }.intersect(allTargets)
                if (availableTargets.isNotEmpty()) {
                    aliasesBuilder.add(Alias(it.key, availableTargets))
                }
            }

        // filter out all groups consisting of less than one member
        aliasesBuilder.removeIf { it.targets.size < 2 }
        aliasesBuilder.removeIf { it.targets == allTargets }
        // Remove all duplicating groups. At this point, aliases are sorted so
        // that more specific groups are before more common groups, so we'll remove
        // more common groups here.
        val toRemove = mutableListOf<Int>()
        for (i in aliasesBuilder.indices) {
            for (j in i + 1 until aliasesBuilder.size) {
                if (aliasesBuilder[j].targets == aliasesBuilder[i].targets) {
                    toRemove.add(j)
                }
            }
        }
        toRemove.sortDescending()
        toRemove.forEach {
            aliasesBuilder.removeAt(it)
        }
        // reverse the order to place common group first
        aliases = aliasesBuilder.reversed()
    }

    override fun formatHeader(targets: Set<Target>): String {
        return buildString {
            append(
                targets.asSequence().map { it.name }.sorted().joinToString(
                    prefix = TARGETS_LIST_PREFIX,
                    postfix = TARGETS_LIST_SUFFIX,
                    separator = TARGETS_DELIMITER
                )
            )
            aliases.forEach {
                append("\n// Alias: ${it.name} => [")
                append(it.targets.map { it.name }.sorted().joinToString(TARGETS_DELIMITER))
                append(TARGETS_LIST_SUFFIX)
            }
        }
    }

    override fun formatDeclarationTargets(targets: Set<Target>): String {
        val targetsMut = targets.toMutableSet()
        val resultingTargets = mutableListOf<String>()
        for (alias in aliases) {
            if (targetsMut.containsAll(alias.targets)) {
                targetsMut.removeAll(alias.targets)
                resultingTargets.add(alias.name)
            }
        }
        resultingTargets.addAll(targetsMut.map { it.name })
        return resultingTargets.sorted().joinToString(
            prefix = TARGETS_LIST_PREFIX,
            postfix = TARGETS_LIST_SUFFIX,
            separator = TARGETS_DELIMITER
        )
    }
}