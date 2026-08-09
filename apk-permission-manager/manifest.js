/*
 * AndroidManifest.xml permission helpers built on top of axml.js.
 * Only concerned with <uses-permission> / <uses-permission-sdk-23>
 * elements directly under <manifest>.
 */
(function (global) {
  'use strict';

  var AXML = global.AXML || (typeof require === 'function' ? require('./axml.js') : null);
  var PERMISSION_TAGS = ['uses-permission', 'uses-permission-sdk-23'];

  function attrValueString(doc, attr) {
    if (attr.valueType === 0x03 && attr.valueData !== AXML.NO_INDEX) {
      return doc.strings[attr.valueData];
    }
    if (attr.rawValue !== AXML.NO_INDEX) return doc.strings[attr.rawValue];
    return null;
  }

  function nameAttrIndex(doc, node) {
    for (var i = 0; i < node.attributes.length; i++) {
      var nm = doc.strings[node.attributes[i].name];
      if (nm === 'name') return i;
    }
    return -1;
  }

  // Returns [{ name, tag, nodeIndex }] for each uses-permission* element,
  // in document order. nodeIndex is the index of the elemStart node in doc.nodes.
  function listPermissionNodes(doc) {
    var out = [];
    for (var i = 0; i < doc.nodes.length; i++) {
      var n = doc.nodes[i];
      if (n.type !== 'elemStart') continue;
      var tag = doc.strings[n.name];
      if (PERMISSION_TAGS.indexOf(tag) === -1) continue;
      var ai = nameAttrIndex(doc, n);
      if (ai === -1) continue;
      var name = attrValueString(doc, n.attributes[ai]);
      if (!name) continue;
      out.push({ name: name, tag: tag, nodeIndex: i });
    }
    return out;
  }

  function listPermissions(doc) {
    return listPermissionNodes(doc).map(function (p) { return { name: p.name, tag: p.tag }; });
  }

  // Find the elemEnd node index matching the elemStart at startIdx, by
  // tracking element nesting depth across the flat node stream.
  function findMatchingEnd(doc, startIdx) {
    var depth = 0;
    for (var i = startIdx; i < doc.nodes.length; i++) {
      var n = doc.nodes[i];
      if (n.type === 'elemStart') depth++;
      else if (n.type === 'elemEnd') {
        depth--;
        if (i > startIdx && depth === 0) return i;
      }
    }
    return -1;
  }

  function removePermission(doc, name) {
    var nodes = listPermissionNodes(doc);
    var target = null;
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].name === name) { target = nodes[i]; break; }
    }
    if (!target) return false;
    var endIdx = findMatchingEnd(doc, target.nodeIndex);
    if (endIdx === -1) throw new Error('Malformed manifest: no matching end tag for ' + name);
    doc.nodes.splice(target.nodeIndex, endIdx - target.nodeIndex + 1);
    return true;
  }

  function findManifestEndIndex(doc) {
    for (var i = doc.nodes.length - 1; i >= 0; i--) {
      var n = doc.nodes[i];
      if (n.type === 'elemEnd' && doc.strings[n.name] === 'manifest') return i;
    }
    return -1;
  }

  function findTemplateElement(doc, tag) {
    for (var i = 0; i < doc.nodes.length; i++) {
      var n = doc.nodes[i];
      if (n.type === 'elemStart' && doc.strings[n.name] === tag && nameAttrIndex(doc, n) !== -1) {
        return n;
      }
    }
    return null;
  }

  // Adds <uses-permission android:name="name"/> (or the sdk-23 variant)
  // as the last child of <manifest>. Clones ns/name string indices and
  // attribute shape from an existing uses-permission* element when one
  // is present, so namespace/attribute wiring stays correct without
  // having to fabricate it from scratch.
  function addPermission(doc, name, tag) {
    tag = tag || 'uses-permission';
    var existing = listPermissionNodes(doc);
    for (var i = 0; i < existing.length; i++) {
      if (existing[i].name === name && existing[i].tag === tag) return false; // already present
    }

    var template = findTemplateElement(doc, tag) || findTemplateElement(doc, PERMISSION_TAGS[0]);
    var elemNs, elemNameIdx, attrNs, attrNameIdx, attrTemplate;

    if (template) {
      elemNs = template.ns;
      var ai = nameAttrIndex(doc, template);
      attrTemplate = template.attributes[ai];
      attrNs = attrTemplate.ns;
      attrNameIdx = attrTemplate.name;
      elemNameIdx = AXML.internString(doc, tag);
    } else {
      // No existing uses-permission element anywhere: fall back to
      // locating the "android" namespace URI and a "name" attribute
      // string already used elsewhere in the manifest.
      var androidUri = AXML.findString(doc.strings, 'http://schemas.android.com/apk/res/android');
      var nameStr = AXML.findString(doc.strings, 'name');
      if (androidUri === -1 || nameStr === -1) {
        throw new Error('Could not find a template to add a new permission from.');
      }
      elemNs = AXML.NO_INDEX;
      attrNs = androidUri;
      attrNameIdx = nameStr;
      elemNameIdx = AXML.internString(doc, tag);
    }

    var valueIdx = AXML.internString(doc, name);

    var startNode = {
      type: 'elemStart', lineNumber: 0, comment: AXML.NO_INDEX,
      ns: elemNs, name: elemNameIdx, idIndex: 0, classIndex: 0, styleIndex: 0,
      attributes: [{
        ns: attrNs, name: attrNameIdx, rawValue: valueIdx,
        valueSize: 8, valueRes0: 0, valueType: 0x03, valueData: valueIdx
      }]
    };
    var endNode = { type: 'elemEnd', lineNumber: 0, comment: AXML.NO_INDEX, ns: elemNs, name: elemNameIdx };

    var insertAt = findManifestEndIndex(doc);
    if (insertAt === -1) throw new Error('Could not find </manifest> to insert permission before.');
    doc.nodes.splice(insertAt, 0, startNode, endNode);
    return true;
  }

  var ManifestPerms = {
    listPermissions: listPermissions,
    removePermission: removePermission,
    addPermission: addPermission,
    PERMISSION_TAGS: PERMISSION_TAGS
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = ManifestPerms;
  global.ManifestPerms = ManifestPerms;
})(typeof window !== 'undefined' ? window : global);
