import json,copy
from pathlib import Path
p=Path('/tmp/noah-native-research/typegen'); d=json.loads((p/'openapi.json').read_text()); schemas=d['components']['schemas']; fixes=[]
for name,s in list(schemas.items()):
 for key,prop in s.get('properties',{}).items():
  parts=prop.get('anyOf',prop.get('oneOf',[]))
  if len(parts)==2 and any(x=={'type':'null'} for x in parts):
   ref=next(x['$ref'] for x in parts if '$ref' in x); inner=copy.deepcopy(schemas[ref.split('/')[-1]])
   inner['type']=[inner['type'],'null']
   if 'enum' in inner: inner['enum'].append(None)
   s['properties'][key]=inner; fixes.append(f'{name}.{key}')
n=schemas['NotificationData']; mapping={}; refs=[]
for variant in n['oneOf']:
 ref,tag=variant['allOf']; payload=copy.deepcopy(schemas[ref['$ref'].split('/')[-1]])
 tagvalue=tag['properties']['notification_type']['enum'][0]
 name='NotificationData'+''.join(x.title() for x in tagvalue.split('_'))
 payload['properties'].update(tag['properties']); payload['required']+=tag['required']; schemas[name]=payload
 url='#/components/schemas/'+name; refs.append({'$ref':url}); mapping[tagvalue]=url
n['oneOf']=refs; n['discriminator']={'propertyName':'notification_type','mapping':mapping}
(p/'openapi-normalized.json').write_text(json.dumps(d,indent=2)); print(fixes)
