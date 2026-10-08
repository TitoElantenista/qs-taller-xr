"""Builds small single-assembly IFC files out of the Tekla sample, as a workshop folder would hold."""
import sys, collections
import ifcopenshell

src = ifcopenshell.open(sys.argv[1])
assemblies = src.by_type("IfcElementAssembly")
sized = []
for a in assemblies:
    parts = [o for rel in a.IsDecomposedBy for o in rel.RelatedObjects]
    sized.append((len(parts), a, parts))
sized.sort(key=lambda t: -t[0])
print("assemblies", len(sized), "largest", [t[0] for t in sized[:5]])
picks = [sized[0], sized[len(sized) // 50], sized[len(sized) // 4]]
for index, (count, assembly, parts) in enumerate(picks):
    out = ifcopenshell.file(schema=src.schema)
    out.add(src.by_type("IfcProject")[0])
    for rel in assembly.IsDecomposedBy:
        out.add(rel)
    for obj in [assembly] + parts:
        for rel in obj.IsDefinedBy:
            if len(rel.RelatedObjects) == 1:
                out.add(rel)
        for rel in getattr(obj, "HasAssociations", []):
            if len(rel.RelatedObjects) <= 3:
                out.add(rel)
    classes = collections.Counter(p.is_a() for p in parts)
    name = f"sample_{index}_{(assembly.Name or 'assembly').replace('/', '_').replace(' ', '_')}.ifc"
    out.write(name)
    print(name, count, dict(classes))
